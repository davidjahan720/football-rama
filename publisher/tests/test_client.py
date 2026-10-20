import json
import socket
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

import pytest

from football_rama_publisher import client
from football_rama_publisher.client import FetchError, fetch_matches

from conftest import FAKE_TOKEN, FakeOpener, FakeResponse, http_error

COMPS = ["PL", "FL1", "CL"]
D1, D2 = date(2026, 10, 20), date(2026, 10, 22)


def ok(payload) -> FakeResponse:
    return FakeResponse(json.dumps(payload).encode("utf-8"))


def call(opener, sleeps=None):
    return fetch_matches(FAKE_TOKEN, D1, D2, COMPS, opener=opener,
                         sleep=(sleeps.append if sleeps is not None else lambda s: None))


def test_request_shape(api_payload):
    opener = FakeOpener(ok(api_payload))
    matches = call(opener)
    assert len(matches) == len(api_payload["matches"])
    req, timeout = opener.requests[0]
    parts = urllib.parse.urlsplit(req.full_url)
    assert (parts.scheme, parts.netloc, parts.path) == ("https", "api.football-data.org", "/v4/matches")
    assert urllib.parse.parse_qs(parts.query) == {"competitions": ["PL,FL1,CL"], "dateFrom": ["2026-10-20"],
                                                  "dateTo": ["2026-10-22"]}
    assert req.get_header("X-auth-token") == FAKE_TOKEN
    assert FAKE_TOKEN not in req.full_url
    assert req.get_method() == "GET"
    assert timeout == client.TIMEOUT_S


def test_429_then_success_honours_retry_after(api_payload):
    sleeps = []
    opener = FakeOpener(http_error(429, {"Retry-After": "7"}), http_error(429, {"X-RequestCounter-Reset": "12"}),
                        ok(api_payload))
    assert call(opener, sleeps)
    assert sleeps == [7.0, 12.0]
    assert len(opener.requests) == 3


def test_429_wait_is_bounded(api_payload):
    sleeps = []
    call(FakeOpener(http_error(429, {"Retry-After": "86400"}), ok(api_payload)), sleeps)
    call(FakeOpener(http_error(429, {"Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT"}), ok(api_payload)), sleeps)
    call(FakeOpener(http_error(429), ok(api_payload)), sleeps)
    assert sleeps == [client.MAX_WAIT_S, client.DEFAULT_WAIT_S, client.DEFAULT_WAIT_S]


def test_429_max_two_retries():
    sleeps = []
    opener = FakeOpener(http_error(429), http_error(429), http_error(429), http_error(429))
    with pytest.raises(FetchError, match="429"):
        call(opener, sleeps)
    assert len(opener.requests) == 3 and len(sleeps) == 2


@pytest.mark.parametrize("code", [400, 401, 403, 404, 500, 502, 503])
def test_http_errors_fail_without_retry(code):
    opener = FakeOpener(http_error(code))
    with pytest.raises(FetchError, match=str(code)) as exc:
        call(opener)
    assert len(opener.requests) == 1
    assert FAKE_TOKEN not in str(exc.value)


@pytest.mark.parametrize("error", [urllib.error.URLError(socket.gaierror("dns")), TimeoutError(), ConnectionResetError()])
def test_network_errors(error):
    with pytest.raises(FetchError):
        call(FakeOpener(error))


@pytest.mark.parametrize("body", [b"<html>", b"\xff\xfe", b"[]", b'{"matches": null}', b'{"errorCode": 400}'])
def test_invalid_payload(body):
    with pytest.raises(FetchError):
        call(FakeOpener(FakeResponse(body)))


def test_oversized_body(monkeypatch):
    monkeypatch.setattr(client, "MAX_BODY_BYTES", 10)
    with pytest.raises(FetchError, match="volumineuse"):
        call(FakeOpener(FakeResponse(b'{"matches": []}')))


def test_empty_token_refused():
    with pytest.raises(FetchError):
        fetch_matches("", D1, D2, COMPS, opener=FakeOpener())


def test_redirect_is_refused_and_token_not_forwarded():
    """Security audit: a redirect must never carry X-Auth-Token to another host (no real socket used)."""
    import email.message
    import io
    import urllib.response

    hits = []

    class FakeHttps(urllib.request.BaseHandler):
        handler_order = 100  # before the real HTTP(S) handlers

        def _open(self, req):
            hits.append((req.full_url, req.get_header("X-auth-token")))
            headers = email.message.Message()
            headers["Location"] = "http://evil.example/steal"
            resp = urllib.response.addinfourl(io.BytesIO(b""), headers, req.full_url, 302)
            resp.msg = "Found"
            return resp

        https_open = http_open = _open

    # Same handler chain as the production opener, with the network replaced.
    opener = urllib.request.build_opener(client._NoRedirect, FakeHttps)
    req = urllib.request.Request(client.API_URL, headers={"X-Auth-Token": FAKE_TOKEN})
    with pytest.raises(urllib.error.HTTPError) as exc:
        opener.open(req, timeout=5)
    assert exc.value.code == 302
    assert [u for u, _ in hits] == [client.API_URL]  # evil.example never requested
    # The production opener really uses the no-redirect handler.
    assert any(isinstance(h, client._NoRedirect) for h in client._urlopen.__self__.handlers)


def test_redirect_maps_to_fetch_error(monkeypatch):
    monkeypatch.setattr(client, "_urlopen", FakeOpener(http_error(302)))
    with pytest.raises(FetchError) as exc:
        fetch_matches(FAKE_TOKEN, D1, D2, COMPS, sleep=lambda s: None)
    assert FAKE_TOKEN not in str(exc.value)
