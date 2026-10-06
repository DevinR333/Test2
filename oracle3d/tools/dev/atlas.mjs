import fs from 'fs'; import {PNG} from 'pngjs';
const w=JSON.parse(fs.readFileSync('dev-data/world/world.json'));
const pool=PNG.sync.read(fs.readFileSync('dev-data/world/pool.png'));
const list=process.argv.slice(2).map(Number);
const out=new PNG({width:256*list.length+8*(list.length-1),height:256});
list.forEach((t,k)=>{
  const idx=new Uint16Array(new Uint8Array(Buffer.from(w.tilesets[t].frames[0],'base64')).buffer);
  for(let id=0;id<256;id++){const p=idx[id];const ox=(p%256)*16,oy=Math.floor(p/256)*16;
    for(let y=0;y<16;y++)for(let x=0;x<16;x++){const s=((oy+y)*pool.width+ox+x)*4,d=(((id>>4)*16+y)*out.width+k*264+(id&15)*16+x)*4;for(let c=0;c<4;c++)out.data[d+c]=pool.data[s+c];}}
});
fs.writeFileSync(process.env.OUT,PNG.sync.write(out));
