import http from 'node:http';
import { randomBytes, createHash, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile, rename, unlink, readdir } from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const scrypt = promisify(scryptCallback);
const escape = s => String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const validId = id => /^[A-Za-z0-9_-]{43}$/.test(id);
const hash = value => createHash('sha256').update(value).digest();

/** Standalone optional copy-sharing service. It never connects to Telegram. */
export async function createSharingServer({ directory, ownerToken, publicOrigin, maxBytes = 512*1024*1024, clock = Date.now, maxShares = 1000 }) {
  if (!ownerToken || ownerToken.length < 32) throw new Error('Set a long OWNER_TOKEN (at least 32 characters).');
  const origin = new URL(publicOrigin);
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') throw new Error('PUBLIC_ORIGIN must be an HTTPS origin. Terminate TLS at a trusted reverse proxy.');
  await mkdir(directory,{recursive:true,mode:0o700});
  const tokenHash = hash(ownerToken);
  const shares = new Map(); const attempts = new Map(); const uploading = new Set();
  for (const file of await readdir(directory)) {
    if (!file.endsWith('.json')) continue;
    const item = JSON.parse(await readFile(path.join(directory,file),'utf8'));
    if (validId(item.id)) shares.set(item.id,item);
  }
  const save = async item => {
    const target = path.join(directory,`${item.id}.json`);
    await writeFile(`${target}.new`,JSON.stringify(item),{mode:0o600}); await rename(`${target}.new`,target);
  };
  const remove = async id => {
    shares.delete(id); await Promise.all(['.json','.bin','.partial'].map(ext=>unlink(path.join(directory,id+ext)).catch(e=>{if(e.code!=='ENOENT')throw e;})));
  };
  const clean = async () => { for(const [id,item] of shares) if(item.expiresAt<=clock())await remove(id); };
  const headers = { 'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer', 'Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'" };
  const reply = (res,status,body,type='application/json; charset=utf-8') => {res.writeHead(status,{...headers,'Content-Type':type});res.end(typeof body==='string'?body:JSON.stringify(body));};
  function authenticated(req) { const header=req.headers.authorization||'';return header.startsWith('Bearer ')&&timingSafeEqual(hash(header.slice(7)),tokenHash); }
  function rate(req,key,limit=12) {
    const now=clock(),name=`${req.socket.remoteAddress}:${key}`;
    if(attempts.size>=10_000&&!attempts.has(name)){for(const [k,v] of attempts)if(v.until<=now)attempts.delete(k);if(attempts.size>=10_000)return false;}
    let bucket=attempts.get(name);if(!bucket||bucket.until<=now){bucket={until:now+60_000,count:0};attempts.set(name,bucket);}
    bucket.count++; if(attempts.size>5000) for(const [k,v] of attempts)if(v.until<=now)attempts.delete(k);
    return bucket.count<=limit;
  }
  async function body(req,max=4096) {let bytes=0;const chunks=[];for await(const c of req){bytes+=c.length;if(bytes>max)throw Object.assign(new Error('Body too large'),{status:413});chunks.push(c);}return Buffer.concat(chunks).toString('utf8');}
  const server=http.createServer(async(req,res)=>{
    try {
      const url=new URL(req.url,'http://local');const segments=url.pathname.split('/').filter(Boolean);
      if(req.method==='GET'&&url.pathname==='/health'){reply(res,200,{ok:true});return;}
      if(segments[0]==='api'){
        if(!authenticated(req)){reply(res,401,{error:'Unauthorized'});return;}
        if(req.method==='DELETE'&&segments.length===3&&segments[1]==='shares'&&validId(segments[2])){
          await remove(segments[2]);reply(res,200,{revoked:true});return;
        }
        if(req.method==='GET'&&url.pathname==='/api/shares'){
          await clean();reply(res,200,[...shares.values()].map(({passwordHash,salt,...safe})=>safe));return;
        }
        if(req.method!=='POST'||url.pathname!=='/api/shares'){reply(res,404,{error:'Not found'});return;}
        if(!rate(req,'upload',20)){reply(res,429,{error:'Too many requests'});return;}
        await clean();if(shares.size>=maxShares){reply(res,507,{error:'Share capacity reached'});return;}
        const length=Number(req.headers['content-length']);
        if(!Number.isSafeInteger(length)||length<0||length>maxBytes){reply(res,length>maxBytes?413:411,{error:'A valid bounded Content-Length is required'});return;}
        const encoded=String(req.headers['x-file-name']||'');if(encoded.length>1024){reply(res,400,{error:'Filename too long'});return;}
        const name=Buffer.from(encoded,'base64url').toString('utf8');
        if(!name||name.length>180||/[\x00-\x1f\x7f/\\]/.test(name)||name==='.'||name==='..'){reply(res,400,{error:'Invalid filename'});return;}
        const expiry=Number(req.headers['x-share-expires-in']||86400);
        if(!Number.isSafeInteger(expiry)||expiry<300||expiry>604800){reply(res,400,{error:'Expiry must be 5 minutes to 7 days'});return;}
        const expected=String(req.headers['x-content-sha256']||'');if(!/^[a-f0-9]{64}$/.test(expected)){reply(res,400,{error:'SHA-256 is required'});return;}
        const uploadId=String(req.headers['x-upload-id']||'');
        if(uploadId&&!/^[a-zA-Z0-9-]{1,80}$/.test(uploadId)){reply(res,400,{error:'Invalid upload identifier'});return;}
        const prior=uploadId&&[...shares.values()].find(s=>s.uploadId===uploadId);
        if(prior){req.resume();if(prior.sha256!==expected){reply(res,409,{error:'Upload identifier already used for different content'});return;}reply(res,201,{id:prior.id,url:new URL(`/s/${prior.id}`,origin).toString(),expiresAt:prior.expiresAt});return;}
        if(uploadId&&uploading.has(uploadId)){reply(res,409,{error:'Upload is already in progress; retry later'});return;}
        const password=Buffer.from(String(req.headers['x-share-password']||''),'base64url').toString('utf8');if(password.length>256){reply(res,400,{error:'Password too long'});return;}
        const id=randomBytes(32).toString('base64url'),target=path.join(directory,id+'.bin'),temp=path.join(directory,id+'.partial');
        const digest=createHash('sha256');let received=0;
        const meter=new Transform({transform(chunk,encoding,callback){received+=chunk.length;if(received>maxBytes||received>length){callback(Object.assign(new Error('Upload too large'),{status:413}));return;}digest.update(chunk);callback(null,chunk);}});
        if(uploadId)uploading.add(uploadId);
        try {
          await pipeline(req,meter,createWriteStream(temp,{flags:'wx',mode:0o600}));
          if(received!==length||digest.digest('hex')!==expected)throw Object.assign(new Error('Content checksum mismatch'),{status:422});
          await rename(temp,target);
          const salt=password?randomBytes(16).toString('hex'):'';
          const item={id,name,uploadId,sha256:expected,size:received,createdAt:clock(),expiresAt:clock()+expiry*1000,salt,passwordHash:password?(await scrypt(password,salt,32)).toString('hex'):''};
          await save(item);shares.set(id,item);
          reply(res,201,{id,url:new URL(`/s/${id}`,origin).toString(),expiresAt:item.expiresAt});
        } catch(e){await unlink(temp).catch(()=>{});await unlink(target).catch(()=>{});throw e;}finally{if(uploadId)uploading.delete(uploadId);}
        return;
      }
      const id=segments[1];if(segments.length!==2||!validId(id)||!['s','d'].includes(segments[0])){reply(res,404,{error:'Not found'});return;}
      const item=shares.get(id);if(!item||item.expiresAt<=clock()){if(item)await remove(id);reply(res,404,{error:'Link is unavailable or expired'});return;}
      if(segments[0]==='s'&&req.method==='GET'){
        const password=item.passwordHash?'<label>Password <input name="password" type="password" required autocomplete="off"></label>':'';
        reply(res,200,`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>TG Drive shared file</title><style>body{font:18px/1.6 system-ui;max-width:600px;margin:10vh auto;padding:24px;background:#f4f7f3;color:#173d30}main{background:white;border-radius:20px;padding:30px}input,button{font:inherit;padding:12px;margin:12px 0;display:block}button{background:#147d72;color:white;border:0;border-radius:10px}</style><main><p>TG Drive · Shared copy</p><h1>${escape(item.name)}</h1><p>${item.size} bytes · Expires ${escape(new Date(item.expiresAt).toISOString())}</p><form action="/d/${id}" method="post">${password}<button>Download file</button></form><p>Revocation prevents future access. A downloaded copy cannot be recalled.</p></main></html>`,'text/html; charset=utf-8');return;
      }
      if(segments[0]!=='d'||!['GET','POST','HEAD'].includes(req.method)){reply(res,405,{error:'Method not allowed'});return;}
      if(item.passwordHash){
        if(!rate(req,`password:${id}`,6)){reply(res,429,{error:'Too many attempts. Try later.'});return;}
        if(req.method!=='POST'){reply(res,401,{error:'Password required'});return;}
        const password=new URLSearchParams(await body(req)).get('password')||'';
        if(password.length>256||!timingSafeEqual(await scrypt(password,item.salt,32),Buffer.from(item.passwordHash,'hex'))){reply(res,401,{error:'Incorrect password'});return;}
      }
      res.writeHead(200,{...headers,'Content-Type':'application/octet-stream','Content-Length':item.size,
        'Content-Disposition':`attachment; filename="download"; filename*=UTF-8''${encodeURIComponent(item.name).replace(/['()*]/g,c=>'%'+c.charCodeAt(0).toString(16))}`});
      if(req.method==='HEAD')res.end();else await pipeline(createReadStream(path.join(directory,id+'.bin')),res);
    }catch(e){if(!res.headersSent&&!res.destroyed)reply(res,e.status||500,{error:e.status?e.message:'Service operation failed'});else res.destroy();}
  });
  server.requestTimeout=20*60*1000;server.headersTimeout=30_000;server.maxHeadersCount=50;
  return server;
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const server=await createSharingServer({directory:process.env.DATA_DIR||'./private-data',ownerToken:process.env.OWNER_TOKEN,publicOrigin:process.env.PUBLIC_ORIGIN,maxBytes:Number(process.env.MAX_UPLOAD_BYTES||536870912)});
  server.listen(Number(process.env.PORT||8080),process.env.HOST||'127.0.0.1',()=>console.log('TG Drive sharing service listening. Configure HTTPS reverse proxy before use.'));
}
