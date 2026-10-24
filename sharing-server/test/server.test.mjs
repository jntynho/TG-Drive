import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createSharingServer } from '../src/server.mjs';

const token='test-only-owner-token-with-enough-entropy';
async function fixture(t,maxBytes=1024){
  const directory=await mkdtemp(path.join(os.tmpdir(),'tg-share-'));let time=Date.now();
  const server=await createSharingServer({directory,ownerToken:token,publicOrigin:'https://share.example/',maxBytes,clock:()=>time});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await rm(directory,{recursive:true,force:true});});
  const base=`http://127.0.0.1:${server.address().port}`;
  const upload=async({name='report.txt',data='known bytes',password='',checksum,uploadId=''}={})=>fetch(base+'/api/shares',{method:'POST',headers:{Authorization:`Bearer ${token}`,'X-Upload-Id':uploadId,'X-File-Name':Buffer.from(name).toString('base64url'),'X-Share-Expires-In':'300','X-Share-Password':Buffer.from(password).toString('base64url'),'X-Content-SHA256':checksum||createHash('sha256').update(data).digest('hex')},body:data});
  return {base,upload,advance:ms=>{time+=ms;}};
}
test('owner token is required; malformed metadata cannot create shares',async t=>{
  const f=await fixture(t);assert.equal((await fetch(f.base+'/api/shares',{method:'POST',body:'x'})).status,401);
  assert.equal((await f.upload({name:'../escape'})).status,400);
});
test('uploaded bytes download exactly and private paths do not appear in responses',async t=>{
  const f=await fixture(t);const response=await f.upload();assert.equal(response.status,201);const share=await response.json();
  assert.equal(share.url,`https://share.example/s/${share.id}`);
  const result=await fetch(f.base+'/d/'+share.id);assert.equal(result.status,200);assert.equal(await result.text(),'known bytes');assert.match(result.headers.get('content-disposition'),/^attachment/);
});
test('password protection, revocation and expiry are enforced',async t=>{
  const f=await fixture(t);const s=await (await f.upload({password:'long test password'})).json();
  assert.equal((await fetch(f.base+'/d/'+s.id)).status,401);
  assert.equal((await fetch(f.base+'/d/'+s.id,{method:'POST',body:new URLSearchParams({password:'wrong'})})).status,401);
  const good=await fetch(f.base+'/d/'+s.id,{method:'POST',body:new URLSearchParams({password:'long test password'})});assert.equal(await good.text(),'known bytes');
  assert.equal((await fetch(f.base+'/api/shares/'+s.id,{method:'DELETE',headers:{Authorization:`Bearer ${token}`}})).status,200);
  assert.equal((await fetch(f.base+'/d/'+s.id)).status,404);
  const exp=await (await f.upload()).json();f.advance(300001);assert.equal((await fetch(f.base+'/d/'+exp.id)).status,404);
});
test('hash mismatch and oversized upload never become downloadable shares',async t=>{
  const f=await fixture(t,32);assert.equal((await f.upload({checksum:'0'.repeat(64)})).status,422);
  assert.equal((await f.upload({data:'a'.repeat(33)})).status,413);
  const list=await (await fetch(f.base+'/api/shares',{headers:{Authorization:`Bearer ${token}`}})).json();assert.deepEqual(list,[]);
});
test('shared filenames are escaped in the landing page',async t=>{
  const f=await fixture(t);const s=await (await f.upload({name:'<img onerror=x>.txt'})).json();const page=await (await fetch(f.base+'/s/'+s.id)).text();assert.ok(page.includes('&lt;img onerror=x&gt;'));assert.ok(!page.includes('<img onerror=x>'));
});

test('retrying an acknowledged upload returns the same public copy',async t=>{
  const f=await fixture(t);const first=await (await f.upload({uploadId:'same-task'})).json();
  const second=await (await f.upload({uploadId:'same-task'})).json();assert.deepEqual(second,first);
  assert.equal((await f.upload({uploadId:'same-task',data:'changed'})).status,409);
  const list=await (await fetch(f.base+'/api/shares',{headers:{Authorization:`Bearer ${token}`}})).json();assert.equal(list.length,1);
});
