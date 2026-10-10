import { beforeEach, describe, expect, it } from "vitest";
import { call, connect, fillQueue, hubSql, pairDevice, roundTrip, send, setupFirstDevice, wipeHub, type Json } from "./helpers";

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

  it("sends the backlog in pages of 100 messages, the next page after an ack frame", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const seqs = await fillQueue(a, [b.id], 150);
    const socket = await connect(b);
    await roundTrip(socket);
    const msgSeqs = () => socket.frames.filter((f: Json) => f.t === "msg").map((f: Json) => f.seq);
    expect(msgSeqs()).toEqual(seqs.slice(0, 100));

    // A live message waits behind the backlog, so a cumulative ack can never skip a queued one.
    const live = await send(a, { to: [b.id] });
    await roundTrip(socket);
    expect(msgSeqs()).toEqual(seqs.slice(0, 100));

    // An ack short of the page does not release the next one.
    socket.ws.send(JSON.stringify({ t: "ack", up_to: seqs[50] }));
    await expect.poll(async () => (await hubSql<{ cursor: number }>("SELECT cursor FROM devices WHERE id = ?", b.id))[0]!.cursor).toBe(seqs[50]);
    await roundTrip(socket);
    expect(msgSeqs()).toHaveLength(100);

    socket.ws.send(JSON.stringify({ t: "ack", up_to: seqs[99] }));
    await socket.next((f: Json) => f.seq === live.seq);
    await roundTrip(socket);
    expect(msgSeqs()).toEqual([...seqs, live.seq]);

    // Once caught up, live messages flow immediately again.
    const next = await send(a, { to: [b.id] });
    expect((await socket.next((f: Json) => f.t === "msg" && f.seq === next.seq)).seq).toBe(next.seq);
    socket.ws.close();
  });

  it("caps a backlog page at 1 MiB", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const big = JSON.stringify({ pad: "x".repeat(100_000) });
    const seqs = await fillQueue(a, [b.id], 15, big);
    const socket = await connect(b);
    await roundTrip(socket);
    const frames = socket.frames.filter((f: Json) => f.t === "msg");
    expect(frames.map((f: Json) => f.seq)).toEqual(seqs.slice(0, 10));
    socket.ws.send(JSON.stringify({ t: "ack", up_to: seqs[9] }));
    await socket.next((f: Json) => f.seq === seqs[14]);
    expect(socket.frames.filter((f: Json) => f.t === "msg").map((f: Json) => f.seq)).toEqual(seqs);
    socket.ws.close();
  });

  it("sends the next page after an ack over HTTP too", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const seqs = await fillQueue(a, [b.id], 101);
    const socket = await connect(b);
    await roundTrip(socket);
    await call("POST", "/v1/acks", { token: b.token, body: { up_to: seqs[99] } });
    expect((await socket.next((f: Json) => f.t === "msg" && f.seq === seqs[100])).seq).toBe(seqs[100]);
    socket.ws.close();
  });

  it("updates last_seen on ack frames", async () => {
    const a = await setupFirstDevice();
    const b = await pairDevice(a);
    const posted = await send(a, { to: [b.id] });
    const socket = await connect(b);
    await socket.next();
    await hubSql("UPDATE devices SET last_seen = ? WHERE id = ?", Date.now() - 11 * 60 * 1000, b.id);
    socket.ws.send(JSON.stringify({ t: "ack", up_to: posted.seq }));
    await expect.poll(async () => (await hubSql<{ cursor: number }>("SELECT cursor FROM devices WHERE id = ?", b.id))[0]!.cursor).toBe(posted.seq);
    const [row] = await hubSql<{ last_seen: number }>("SELECT last_seen FROM devices WHERE id = ?", b.id);
    expect(row!.last_seen).toBeGreaterThan(Date.now() - 60_000);
    socket.ws.close();
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
