import json
from typing import Dict, Set
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

app = FastAPI(title="Glasses Companion Signalling & AI Proxy", version="1.0.0")
rooms: Dict[str, Set[WebSocket]] = {}

@app.get("/health")
async def health():
    return {"status": "ok", "service": "glasses-companion-backend"}

@app.websocket("/ws/call/{room_id}")
async def call_signalling(ws: WebSocket, room_id: str):
    await ws.accept()
    peers = rooms.setdefault(room_id, set())
    peers.add(ws)
    try:
        while True:
            msg = await ws.receive_text()
            for peer in list(peers):
                if peer is not ws:
                    await peer.send_text(msg)
    except WebSocketDisconnect:
        peers.discard(ws)
        if not peers:
            rooms.pop(room_id, None)
