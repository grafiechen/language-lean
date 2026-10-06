/** 检查完整设计数据和本地预览。此检查不代替 Figma 原生导入与视觉验收。 */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const { createHash } = require('node:crypto');
const root=path.resolve(__dirname,'..');
const base=path.join(root,'docs/figma/complete');
const playwrightPath=process.env.LANGUAGE_LEAN_PLAYWRIGHT_PATH||'C:/Users/83575/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright';
const {chromium}=require(playwrightPath);
const data=JSON.parse(fs.readFileSync(path.join(base,'screens.json'),'utf8'));
const coverage=JSON.parse(fs.readFileSync(path.join(base,'coverage.json'),'utf8'));
const ids=new Set(data.screens.map(s=>s.id));
assert.equal(ids.size,data.screens.length,'页面 ID 必须唯一');
const required=['login','home','password-change','wordbooks','wordbook-detail','wordbook-reset','wordbook-remove','dictionary-list','dictionary-detail','dictionary-banned','admin-catalog','admin-create','admin-edit','admin-history','admin-duplicate','admin-conflict','import-upload','import-preview','import-applied','languages','system-dictionaries','system-dictionary-new','system-dictionary-edit','system-item-new','system-item-edit','personal-new','personal-edit','personal-import','contribution-submit','review-queue','review-hidden','review-answer','review-retry','review-resume','review-complete','extra-training','ear-pool','ear-training','audio-missing','audio-no-pronunciation','audio-generating','audio-failed','offline-unprepared','offline-preparing','offline-ready','offline-partial','offline-expired','sync','sync-today','sync-stale','backup','account','admin-accounts','admin-account-new','admin-moderation','admin-moderation-detail','admin-tts','admin-audio'];
for(const id of required)assert(ids.has(id),'缺少第一版界面：'+id);
function walk(nodes,fn){for(const n of nodes){fn(n);if(n.children)walk(n.children,fn);}}
for(const s of data.screens)walk(s.children,n=>{if(n.target)assert(ids.has(n.target),'不存在的流程目标：'+n.target)});
for(const [source,hash] of Object.entries(coverage.sourceHashes))assert.equal(createHash('sha256').update(fs.readFileSync(path.join(root,source))).digest('hex'),hash,'源代码变化后须重新生成设计材料：'+source);
const hidden=data.screens.find(s=>s.id==='review-hidden');
assert(!JSON.stringify(data.screens.find(s=>s.id==='backup').children).includes('导出当前账户数据'),'不能恢复已关闭的个人导出入口');
for(const id of ['password-reset','password-reset-expired','native-language-fallback','dictionary-kana','admin-usage','admin-backups-disabled','admin-backups-ready','admin-backups-history','offline-cache','backup-owner-mismatch'])assert(ids.has(id),'缺少最新界面：'+id);
assert(!JSON.stringify(hidden.children).includes('ねこ'),'隐藏答案画板不能泄露假名');
assert(!JSON.stringify(hidden.children).includes('猫'),'隐藏答案画板不能泄露词条');
const auditDir=path.join(base,'audit');fs.mkdirSync(auditDir,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.LANGUAGE_LEAN_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const page=await browser.newPage({viewport:{width:2200,height:1200}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(pathToFileURL(path.join(base,'preview.html')).href);await page.waitForSelector('.viewport');
  const frames=await page.locator('.viewport').count();assert.equal(frames,data.screens.length*2);
  const issues=await page.evaluate(()=>{
   const found=[];
   for(const root of document.querySelectorAll('.viewport')){
    const rb=root.getBoundingClientRect();
    for(const n of root.querySelectorAll('.node,.value,td,th')){
     const b=n.getBoundingClientRect();
     if(b.width<1||b.left<rb.left-1||b.right>rb.right+1||b.bottom>rb.bottom+1)found.push({id:root.dataset.screen,mobile:root.dataset.mobile,kind:n.className,text:n.textContent.slice(0,50),reason:'画板越界'});
     if(n.scrollWidth>n.clientWidth+2)found.push({id:root.dataset.screen,mobile:root.dataset.mobile,text:n.textContent.slice(0,50),reason:'内容横向溢出'});
    }
   }return found;
  });
  const samples=['login','home','wordbook-detail','dictionary-detail','admin-create','system-dictionaries','system-item-new','import-preview','review-hidden','review-answer','offline-partial','sync','password-reset','admin-usage','admin-backups-history','backup-preview'];
  await page.locator('.toolbar').evaluate(node=>{node.style.visibility='hidden'});
  for(const id of samples){await page.evaluate(id=>window.showScreen(id),id);for(const mobile of [false,true]){const node=page.locator('.viewport[data-mobile="'+mobile+'"]');await node.screenshot({path:path.join(auditDir,id+'-'+(mobile?'mobile':'desktop')+'.png')});}}
  // 所有画板按模块缩略排列，便于逐屏查漏和整体视觉检查。
  const groups=[...new Set(data.screens.map(s=>s.group))];
  for(const [i,group] of groups.entries()){
   await page.evaluate(group=>{
    window.showScreen('');document.querySelector('.toolbar').remove();
    const gallery=document.querySelector('.gallery');gallery.style.display='grid';gallery.style.gridTemplateColumns='repeat(3, 620px)';gallery.style.gap='24px';
    for(const section of [...gallery.children]){
     const id=section.querySelector('.viewport').dataset.screen;const spec=window.designData.screens.find(s=>s.id===id);if(spec.group!==group){section.remove();continue}
     const pair=section.querySelector('.screen-pair');const h=pair.getBoundingClientRect().height;pair.style.width='620px';pair.style.height=(h*.3)+'px';pair.style.position='relative';
     const children=[...pair.children];children.forEach((n,j)=>{n.style.position='absolute';n.style.transform='scale(.3)';n.style.transformOrigin='top left';n.style.left=(j===0?0:460)+'px';n.style.top='0'});
    }
   },group);
   await page.screenshot({path:path.join(auditDir,'board-'+String(i+1).padStart(2,'0')+'.png'),fullPage:true});
   await page.reload();await page.waitForSelector('.viewport');
  }
  assert.equal(errors.length,0,errors.join('\n'));
  const report={status:issues.length?'failed':'local-preview-passed',screenCount:data.screens.length,frameCount:frames,checkedSourceFiles:Object.keys(coverage.sourceHashes),sourceHashCheck:true,localOverflowIssues:issues,consoleErrors:errors,sampleScreenshots:samples.length*2,moduleBoards:groups.length,figmaImportVerified:false,figmaVisualVerified:false};
  fs.writeFileSync(path.join(auditDir,'local-preview-report.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({screens:data.screens.length,frames,issues:issues.length,errors}));
  if(issues.length){console.log(JSON.stringify(issues.slice(0,15),null,2));process.exitCode=1;}
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
