import test from "node:test";
import assert from "node:assert/strict";
import { isAllowedHost, rewriteM3U8 } from "./stream.js";

const allow = (hosts) => {
  const previous = process.env.ALLOWED_STREAM_HOSTS;
  process.env.ALLOWED_STREAM_HOSTS = hosts;
  return () => {
    if (previous === undefined) delete process.env.ALLOWED_STREAM_HOSTS;
    else process.env.ALLOWED_STREAM_HOSTS = previous;
  };
};

test("allows exact configured host only", () => {
  const restore = allow("media.example.com");
  try {
    assert.equal(isAllowedHost(new URL("https://media.example.com/a.mp4")), true);
    assert.equal(isAllowedHost(new URL("https://evil.example.com/a.mp4")), false);
    assert.equal(isAllowedHost(new URL("http://media.example.com/a.mp4")), true);
  } finally { restore(); }
});

test("wildcard only matches a true subdomain", () => {
  const restore = allow("*.example.com");
  try {
    assert.equal(isAllowedHost(new URL("https://cdn.example.com/a.m3u8")), true);
    assert.equal(isAllowedHost(new URL("https://example.com/a.m3u8")), false);
    assert.equal(isAllowedHost(new URL("https://notexample.com/a.m3u8")), false);
  } finally { restore(); }
});

test("rewrites allowed HLS segment and key URLs through gateway", () => {
  const restore = allow("media.example.com");
  try {
    const input = '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\nsegment.ts\n';
    const output = rewriteM3U8(input, new URL("https://media.example.com/path/list.m3u8"));
    assert.match(output, /\/api\/stream\?url=/);
    assert.equal((output.match(/\/api\/stream\?url=/g) || []).length, 2);
  } finally { restore(); }
});

test("does not rewrite unallowlisted HLS targets", () => {
  const restore = allow("media.example.com");
  try {
    const input = "https://other.example.org/segment.ts";
    assert.equal(rewriteM3U8(input, new URL("https://media.example.com/list.m3u8")), input);
  } finally { restore(); }
});
