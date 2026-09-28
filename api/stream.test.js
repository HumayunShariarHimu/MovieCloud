import test from "node:test";
import assert from "node:assert/strict";
import { isAllowedHost, rewriteM3U8 } from "./stream.js";

const allow = (hosts) => hosts.split(",").map((v) => v.trim().toLowerCase());

test("allows exact configured host only", () => {
  const patterns = allow("media.example.com");
  assert.equal(isAllowedHost(new URL("https://media.example.com/a.mp4"), patterns), true);
  assert.equal(isAllowedHost(new URL("https://evil.example.com/a.mp4"), patterns), false);
});

test("wildcard only matches a true subdomain", () => {
  const patterns = allow("*.example.com");
  assert.equal(isAllowedHost(new URL("https://cdn.example.com/a.m3u8"), patterns), true);
  assert.equal(isAllowedHost(new URL("https://example.com/a.m3u8"), patterns), false);
  assert.equal(isAllowedHost(new URL("https://notexample.com/a.m3u8"), patterns), false);
});

test("rewrites allowed HLS segment and key URLs through gateway", () => {
  const patterns = allow("media.example.com");
  const input = '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\nsegment.ts\n';
  const output = rewriteM3U8(input, new URL("https://media.example.com/path/list.m3u8"), patterns);
  assert.match(output, /\/api\/stream\?url=/);
  assert.equal((output.match(/\/api\/stream\?url=/g) || []).length, 2);
});

test("does not rewrite unallowlisted HLS targets", () => {
  const patterns = allow("media.example.com");
  const input = "https://other.example.org/segment.ts";
  assert.equal(rewriteM3U8(input, new URL("https://media.example.com/list.m3u8"), patterns), input);
});
