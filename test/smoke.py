"""Silent smoke test against the phone, using the same library as Home Assistant's DLNA integration.

Usage: python smoke.py <phone-ip> <this-pc-ip>
Needs: pip install async-upnp-client. Plays nothing: only sets a URI, reads state and stops.
"""
import asyncio
import sys

from async_upnp_client.aiohttp import AiohttpNotifyServer, AiohttpRequester
from async_upnp_client.client_factory import UpnpFactory
from async_upnp_client.profiles.dlna import DmrDevice, TransportState
from async_upnp_client.search import async_search

PHONE, PC = sys.argv[1], sys.argv[2]
DESC = f"http://{PHONE}:49494/desc.xml"


async def main():
    found = []

    async def on_search(headers):
        if PHONE in headers.get("location", ""):
            found.append(headers["st"])

    await async_search(on_search, timeout=4, search_target="ssdp:all", source=(PC, 0))
    print("ssdp:", sorted(set(found)))
    assert "urn:schemas-upnp-org:device:MediaRenderer:1" in found, "M-SEARCH not answered"

    requester = AiohttpRequester()
    device = await UpnpFactory(requester, non_strict=False).async_create_device(DESC)  # strict: catches SCPD mistakes
    notify = AiohttpNotifyServer(requester, source=(PC, 0))
    await notify.async_start_server()
    dmr = DmrDevice(device, notify.event_handler)
    events = []
    dmr.on_event = lambda service, variables: events.append((service.service_id, [v.name for v in variables]))

    await dmr.async_subscribe_services(auto_resubscribe=False)
    await dmr.async_update()
    print("state:", dmr.transport_state, "volume:", dmr.volume_level, "muted:", dmr.is_volume_muted)
    assert dmr.has_volume_level

    await dmr.async_set_transport_uri("http://127.0.0.1/none.mp3", "smoke test")
    await asyncio.sleep(2)
    await dmr.async_update()
    assert dmr.transport_state == TransportState.STOPPED, dmr.transport_state
    assert dmr.can_play and dmr.can_stop
    assert dmr.current_track_uri == "http://127.0.0.1/none.mp3", dmr.current_track_uri

    await dmr.async_stop()
    await asyncio.sleep(1)
    print("events:", events)
    assert any("LastChange" in names or "TransportState" in names for _, names in events), "no GENA events received"

    await dmr.async_unsubscribe_services()
    await notify.async_stop_server()
    print("OK")


asyncio.run(main())
