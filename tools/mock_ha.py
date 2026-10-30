#!/usr/bin/env python3
"""
Tiny fake Home Assistant for developing the dock without a real install.

Speaks just enough of the WebSocket API (auth, get_states, subscribe_events, call_service)
and the REST /api/states endpoint. Any token is accepted except "bad".

    pip install websockets
    python3 tools/mock_ha.py [port]     # listens on :8123 by default
    adb reverse tcp:8123 tcp:8123       # then use http://localhost:8123 on the phone
"""
import asyncio
import json
import sys
from datetime import datetime, timedelta, timezone

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8123
clients = set()
states = {}


def put(entity_id, state, **attrs):
    states[entity_id] = {
        "entity_id": entity_id,
        "state": str(state),
        "attributes": attrs,
        "last_changed": datetime.now(timezone.utc).isoformat(),
    }
    return states[entity_id]


put("sensor.model_3_battery_level", 64, unit_of_measurement="%", device_class="battery", friendly_name="Model 3 Battery")
put("sensor.model_3_time_to_full_charge", 95, unit_of_measurement="min", device_class="duration", friendly_name="Model 3 Time to full")
put("binary_sensor.model_3_charging", "on", device_class="battery_charging", friendly_name="Model 3 Charging")
put("sensor.model_3_range", 312, unit_of_measurement="km", device_class="distance", friendly_name="Model 3 Range")
put("climate.living_room_ac", "cool", temperature=22, current_temperature=25.5, target_temp_step=0.5,
    hvac_modes=["off", "cool", "heat", "auto"], friendly_name="Living room AC")
# The adaptive_bedroom_ac integration, steering the AC above.
put("switch.adaptive_bedroom_ac_adaptive_control", "on", friendly_name="Adaptive Bedroom AC Adaptive control")
put("number.adaptive_bedroom_ac_comfort_target", 23.0, min=20.0, max=28.0, step=0.5, unit_of_measurement="°C",
    friendly_name="Adaptive Bedroom AC Comfort target")
put("sensor.adaptive_bedroom_ac_room_temperature", 24.0, unit_of_measurement="°C", device_class="temperature",
    friendly_name="Adaptive Bedroom AC Room temperature")
put("sensor.adaptive_bedroom_ac_status", "holding", friendly_name="Adaptive Bedroom AC Status")
put("cover.living_room_blinds", "open", current_position=100, friendly_name="Living room blinds")
put("cover.bedroom_blinds", "closed", current_position=0, friendly_name="Bedroom blinds")
put("light.desk_lamp", "on", friendly_name="Desk lamp")
put("scene.movie_night", "scening", friendly_name="Movie night")
put("weather.forecast_home", "partlycloudy", temperature=18, temperature_unit="°C", friendly_name="Forecast Home")
put("sensor.pixel_battery_level", 80, unit_of_measurement="%", device_class="battery", friendly_name="Pixel battery")


async def broadcast(entity_id):
    for ws, sub_id in list(clients):
        msg = {"id": sub_id, "type": "event", "event": {
            "event_type": "state_changed",
            "data": {"entity_id": entity_id, "new_state": states[entity_id]},
        }}
        try:
            await ws.send(json.dumps(msg))
        except websockets.ConnectionClosed:
            clients.discard((ws, sub_id))


async def call_service(domain, service, entity_id, data):
    s = states.get(entity_id)
    if s is None:
        return
    a = s["attributes"]
    if domain == "climate":
        if service == "turn_off":
            s["state"] = "off"
        elif service == "turn_on":
            s["state"] = "cool"
        elif service == "set_temperature":
            a["temperature"] = data.get("temperature", a.get("temperature"))
    elif domain == "number" and service == "set_value":
        s["state"] = str(data.get("value", s["state"]))
    elif domain == "cover":
        target = {"open_cover": 100, "close_cover": 0}.get(service)
        if target is not None:
            # Move in steps so the dock shows "opening"/"closing" like a real motor.
            pos = a.get("current_position", 0)
            s["state"] = "opening" if target > pos else "closing"
            while pos != target:
                pos += 10 if target > pos else -10
                a["current_position"] = pos
                await broadcast(entity_id)
                await asyncio.sleep(0.4)
            s["state"] = "open" if target else "closed"
    elif service in ("toggle", "turn_on", "turn_off"):
        s["state"] = {"toggle": "off" if s["state"] == "on" else "on"}.get(service, service.removeprefix("turn_"))
    s["last_changed"] = datetime.now(timezone.utc).isoformat()
    await broadcast(entity_id)


async def handler(ws, _path=None):
    await ws.send(json.dumps({"type": "auth_required", "ha_version": "2026.10.0"}))
    auth = json.loads(await ws.recv())
    if auth.get("access_token") == "bad":
        await ws.send(json.dumps({"type": "auth_invalid", "message": "Invalid access token"}))
        return
    await ws.send(json.dumps({"type": "auth_ok", "ha_version": "2026.10.0"}))
    sub = None
    try:
        async for raw in ws:
            msg = json.loads(raw)
            t, mid = msg.get("type"), msg.get("id")
            if t == "get_states":
                await ws.send(json.dumps({"id": mid, "type": "result", "success": True, "result": list(states.values())}))
            elif t == "subscribe_events":
                sub = (ws, mid)
                clients.add(sub)
                await ws.send(json.dumps({"id": mid, "type": "result", "success": True, "result": None}))
            elif t == "call_service":
                await ws.send(json.dumps({"id": mid, "type": "result", "success": True, "result": {}}))
                print("call_service", msg["domain"], msg["service"], msg["target"], msg.get("service_data"))
                asyncio.create_task(call_service(msg["domain"], msg["service"], msg["target"]["entity_id"], msg.get("service_data") or {}))
    finally:
        clients.discard(sub)


async def rest(path, headers):
    if path == "/api/websocket":
        return None
    if headers.get("Authorization", "") == "Bearer bad":
        return 401, [], b"401: Unauthorized"
    if path == "/api/states":
        return 200, [("Content-Type", "application/json")], json.dumps(list(states.values())).encode()
    return 404, [], b"not found"


async def simulate_charging():
    """Tick the car up a percent every 10 s so the dock has something live to show."""
    while True:
        await asyncio.sleep(10)
        lvl = states["sensor.model_3_battery_level"]
        level = min(100, int(lvl["state"]) + 1)
        lvl["state"] = str(level)
        await broadcast("sensor.model_3_battery_level")
        ttf = states["sensor.model_3_time_to_full_charge"]
        ttf["state"] = str(max(0, int(ttf["state"]) - 3))
        await broadcast("sensor.model_3_time_to_full_charge")


async def main():
    async with websockets.serve(handler, "0.0.0.0", PORT, process_request=rest):
        print(f"mock Home Assistant on :{PORT}")
        await simulate_charging()


if __name__ == "__main__":
    asyncio.run(main())
