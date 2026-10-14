import test from "node:test";
import assert from "node:assert/strict";
import { validateCatalog } from "./catalog.js";

const base = {version: 2, providers: {Demo: {sources: []}}, titles: []};

test("accepts an empty but structurally valid catalog", () => {
  assert.equal(validateCatalog(base), true);
});

test("accepts authorized MP4/HLS title sources", () => {
  assert.equal(validateCatalog({...base, titles: [{id: "sample-title", title: "Sample Title", sources: [{label: "HD", type: "HLS", url: "https://media.example.com/master.m3u8"}]}]}), true);
});

test("accepts Cloudflare Stream playback IDs without exposing an origin URL", () => {
  assert.equal(validateCatalog({...base, titles: [{id: "cdn-title", title: "CDN Title", sources: [{label: "Adaptive HD", type: "HLS", delivery: "cloudflare-stream", playbackId: "abc123_XY-789"}]}]}), true);
});

test("accepts authorized live HLS channels", () => {
  assert.equal(validateCatalog({...base, titles: [{id: "live-channel", title: "Live Channel", sources: [{label: "Live HD", type: "HLS", live: true, url: "https://live.example.com/channel/master.m3u8"}]}]}), true);
  assert.equal(validateCatalog({...base, titles: [{id: "invalid-live", title: "Invalid Live", sources: [{label: "Live", type: "HLS", live: "yes", url: "https://live.example.com/channel.m3u8"}]}]}), false);
});

test("rejects credentials and non-http media URLs", () => {
  assert.equal(validateCatalog({...base, titles: [{id: "bad-title", title: "Bad", sources: [{label: "Bad", type: "MP4", url: "ftp://media.example.com/file.mp4"}]}]}), false);
  assert.equal(validateCatalog({...base, titles: [{id: "bad-title", title: "Bad", sources: [{label: "Bad", type: "MP4", url: "https://user:pass@media.example.com/file.mp4"}]}]}), false);
});

test("rejects unsupported source types and malformed title IDs", () => {
  assert.equal(validateCatalog({...base, titles: [{id: "Bad ID", title: "Bad", sources: [{label: "Bad", type: "DASH", url: "https://media.example.com/file.mpd"}]}]}), false);
});
