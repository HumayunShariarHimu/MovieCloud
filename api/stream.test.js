import test from "node:test";
import assert from "node:assert/strict";
import { isAllowedHost, rewriteM3U8 } from "./stream.js";
const allow = hosts => hosts.split(",").map(v=>v.trim().toLowerCase());

test("allows exact configured host only",()=>{const p=allow("media.example.com");assert.equal(isAllowedHost(new URL("https://media.example.com/a.mp4"),p),true);assert.equal(isAllowedHost(new URL("https://evil.example.com/a.mp4"),p),false);});
test("wildcard matches true subdomains but not apex or lookalikes",()=>{const p=allow("*.example.com");assert.equal(isAllowedHost(new URL("https://cdn.example.com/a.m3u8"),p),true);assert.equal(isAllowedHost(new URL("https://example.com/a.m3u8"),p),false);assert.equal(isAllowedHost(new URL("https://notexample.com/a.m3u8"),p),false);});
test("fails closed when no allowlist is configured",()=>{assert.equal(isAllowedHost(new URL("https://media.example.com/a.mp4"),[]),false);});
test("blocks localhost and private network targets",()=>{const p=allow("localhost,127.0.0.1,10.0.0.1,192.168.1.4,media.example.com");for(const host of ["localhost","127.0.0.1","10.0.0.1","192.168.1.4","172.16.0.4","[::1]"]){assert.equal(isAllowedHost(new URL(`http://${host}/x`),p),false,host);}assert.equal(isAllowedHost(new URL("https://media.example.com/x"),p),true);});
test("rewrites allowed HLS segment and key URLs",()=>{const p=allow("media.example.com");const input='#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\nsegment.ts\n';const output=rewriteM3U8(input,new URL("https://media.example.com/path/list.m3u8"),p);assert.equal((output.match(/\/api\/stream\?url=/g)||[]).length,2);});
test("does not rewrite unallowlisted HLS targets",()=>{const input="https://other.example.org/segment.ts";assert.equal(rewriteM3U8(input,new URL("https://media.example.com/list.m3u8"),allow("media.example.com")),input);});
test("rewrites absolute child playlist and preserves comments",()=>{const out=rewriteM3U8("#EXT-X-VERSION:3\nhttps://media.example.com/child.m3u8\nhttps://other.example.com/no.m3u8",new URL("https://media.example.com/master.m3u8"),allow("media.example.com"));assert.match(out,/\/api\/stream\?url=https%3A%2F%2Fmedia.example.com%2Fchild.m3u8/);assert.match(out,/https:\/\/other.example.com\/no.m3u8/);});
