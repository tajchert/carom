import { beforeEach, describe, expect, it } from "vitest";
import { call, connect, hubSql, pairDevice, send, setupFirstDevice, wipeHub, type Json } from "./helpers";

beforeEach(wipeHub);

describe("WebSocket", () => {
  it("sends the backlog on connect, then live messages, and accepts acks", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const first = await send(a, { to: [b.id] });
    const second = await send(a, { to: [b.id] });

    const socket = await connect(b);
    const backlog = [await socket.next(), await socket.next()];
    expect(backlog.map((f: Json) => [f.t, f.seq])).toEqual([
      ["msg", first.seq],
      ["msg", second.seq],
    ]);
    expect(backlog[0].envelope.from).toBe(a.id);

    const live = await send(a, { to: [b.id] });
    expect((await socket.next()).seq).toBe(live.seq);

    socket.ws.send(JSON.stringify({ t: "ack", up_to: second.seq }));
    socket.ws.send("garbage");
    socket.ws.send(JSON.stringify({ t: "ack", up_to: "x" }));
    // Round-trip through ping to be sure the acks were processed.
    socket.ws.send("ping");
    expect(await socket.next()).toBe("pong");
    await expect.poll(async () => (await hubSql<{ cursor: number }>("SELECT cursor FROM devices WHERE id = ?", b.id))[0]!.cursor).toBe(second.seq);
    expect((await call("GET", "/v1/messages", { token: b.token })).messages.map((m: Json) => m.seq)).toEqual([live.seq]);

    // A reconnect only replays what is still unacknowledged.
    socket.ws.close();
    const again = await connect(b);
    expect((await again.next()).seq).toBe(live.seq);
    again.ws.close();
  });

  it("answers the text ping with pong", async () => {
    const a = await setupFirstDevice();
    const socket = await connect(a);
    socket.ws.send("ping");
    expect(await socket.next()).toBe("pong");
    socket.ws.close();
  });

  it("closes every socket with revoked on reset", async () => {
    const a = await setupFirstDevice();
    const socket = await connect(a);
    await wipeHub();
    expect(await socket.next()).toEqual({ t: "revoked" });
    expect((await socket.closed).code).toBe(4001);
  });
});
