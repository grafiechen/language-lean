/**
 * 在本地模型中执行原生生成器，检查重复运行、字体错误、组件引用和自动布局轴约束。
 * 模型不能证明 Figma 插件运行成功；报告明确保留 actualFigmaVerified=false。
 */
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { colors, screens, tabs } from '../docs/figma/src/screens.mjs';
import { buildFigma } from '../docs/figma/src/figma-builder.mjs';
function model(fonts=[{family:'Microsoft YaHei',style:'Regular'},{family:'Microsoft YaHei',style:'Bold'}]) {
 let sequence=0;const all=new Map(),styleMap=new Map();
 class Node {
  constructor(type){this.type=type;this.id='local:'+ ++sequence;this.name=type;this.children=[];this.parent=null;this._width=100;this._height=100;this.layoutMode='NONE';this.primaryAxisSizingMode='FIXED';this.counterAxisSizingMode='FIXED';this.paddingTop=0;this.paddingBottom=0;this.paddingLeft=0;this.paddingRight=0;this.itemSpacing=0;this.visible=true;this.fills=[];this.fontName=fonts[0];this.fontSize=16;this.characters='';this.properties={};all.set(this.id,this);}
  appendChild(n){if(n.parent)n.parent.children=n.parent.children.filter(c=>c!==n);this.children.push(n);n.parent=this;}
  resize(w,h){assert(w>0&&h>0);this._width=w;this._height=h;this.primaryAxisSizingMode='FIXED';this.counterAxisSizingMode='FIXED';}
  resizeWithoutConstraints(w,h){this.resize(w,h);}
  get x(){
   if(!this.parent||!['VERTICAL','HORIZONTAL'].includes(this.parent.layoutMode))return this._x||0;
   const parent=this.parent;const previous=parent.children.slice(0,parent.children.indexOf(this)).filter(c=>c.visible);
   if(parent.layoutMode==='HORIZONTAL')return parent.paddingLeft+previous.reduce((sum,c)=>sum+c.width+parent.itemSpacing,0);
   return parent.paddingLeft+(parent.counterAxisAlignItems==='CENTER'?(parent.width-parent.paddingLeft-parent.paddingRight-this.width)/2:0);
  }
  set x(value){this._x=value;}
  get y(){
   if(!this.parent||!['VERTICAL','HORIZONTAL'].includes(this.parent.layoutMode))return this._y||0;
   const parent=this.parent;const previous=parent.children.slice(0,parent.children.indexOf(this)).filter(c=>c.visible);
   return parent.paddingTop+(parent.layoutMode==='VERTICAL'?previous.reduce((sum,c)=>sum+c.height+parent.itemSpacing,0):0);
  }
  set y(value){this._y=value;}
  get absoluteBoundingBox(){const parent=this.parent?.absoluteBoundingBox||{x:0,y:0};return {x:parent.x+this.x,y:parent.y+this.y,width:this.width,height:this.height};}
  get width(){const children=this.children.filter(c=>c.visible);if(this.layoutMode==='HORIZONTAL'&&this.primaryAxisSizingMode==='AUTO')return this.paddingLeft+this.paddingRight+children.reduce((sum,c)=>sum+c.width,0)+Math.max(0,children.length-1)*this.itemSpacing;return this._width;}
  get height(){
   if(this.type==='TEXT')return this.textHeight();
   const children=this.children.filter(c=>c.visible);const pad=this.paddingTop+this.paddingBottom;
   if(this.layoutMode==='VERTICAL'&&this.primaryAxisSizingMode==='AUTO')return Math.max(this.minHeight||1,pad+children.reduce((sum,c)=>sum+c.height,0)+Math.max(0,children.length-1)*this.itemSpacing);
   if(this.layoutMode==='HORIZONTAL'&&this.counterAxisSizingMode==='AUTO')return Math.max(this.minHeight||1,pad,...children.map(c=>c.height+pad));
   return this._height;
  }
  textHeight(){if(this.textAutoResize!=='HEIGHT')return this._height;let lines=0;for(const paragraph of this.characters.split('\n')){let used=0;lines++;for(const c of paragraph){const cw=this.fontSize*(c.charCodeAt(0)>255?1:.55);if(used+cw>this.width&&used>0){lines++;used=0;}used+=cw;}}return Math.max(1,lines*this.fontSize*(this.lineHeight?.value||160)/100);}
  set textStyleId(id){const style=styleMap.get(id);assert(style);this.fontName=style.fontName;this.fontSize=style.fontSize;this.lineHeight=style.lineHeight;}
  async setTextStyleIdAsync(id){this.textStyleId=id;}
  setBoundVariable(field,variable){this[field]=variable.value;}
  get componentPropertyDefinitions(){assert(['COMPONENT_SET','COMPONENT'].includes(this.type));return this.properties;}
  addComponentProperty(name,type,value){assert(['COMPONENT','COMPONENT_SET'].includes(this.type));const key=name+'#'+this.id;this.properties[key]={type,defaultValue:value};return key;}
  deleteComponentProperty(key){assert(this.properties[key]);delete this.properties[key];}
  createInstance(){assert.equal(this.type,'COMPONENT');const clone=n=>{const c=new Node(n.type);for(const [k,v] of Object.entries(n)){if(!['id','parent','children'].includes(k))c[k]=v;}for(const child of n.children)c.appendChild(clone(child));return c;};const i=clone(this);i.type='INSTANCE';if(this.parent?.type==='COMPONENT_SET')i.properties={...this.parent.properties};return i;}
  setProperties(props){assert.equal(this.type,'INSTANCE');for(const [key,value] of Object.entries(props)){assert(this.properties[key],'无效组件属性');const targets=this.findAll(n=>n.componentPropertyReferences?.characters===key);assert(targets.length);for(const node of targets)node.characters=value;}}
  findAll(fn){const out=[];for(const c of this.children){if(fn(c))out.push(c);out.push(...c.findAll(fn));}return out;}
  findAllWithCriteria({types}){return this.findAll(n=>types.includes(n.type));}
  async setReactionsAsync(reactions){this.reactions=reactions;for(const r of reactions)for(const a of r.actions)assert(all.has(a.destinationId));}
 }
 const root=new Node('DOCUMENT');let currentPage;
 const variables={createVariableCollection(name){return {name,id:'collection:'+ ++sequence,defaultModeId:'mode',renameMode(){}}},createVariable(name,collection,type){return {name,id:'variable:'+ ++sequence,type,setValueForMode(mode,value){this.value=value;},setVariableCodeSyntax(){}}},setBoundVariableForPaint(paint,field,variable){return {...paint,boundVariables:{[field]:{type:'VARIABLE_ALIAS',id:variable.id}}};}};
 const create=type=>{const n=new Node(type);if(currentPage)currentPage.appendChild(n);return n;};
 const combineAsVariants=(nodes,parent)=>{const set=create('COMPONENT_SET');parent.appendChild(set);for(const node of nodes){Object.assign(set.properties,node.properties);set.appendChild(node);}return set;};
 return {root,get currentPage(){return currentPage;},mixed:Symbol('mixed'),variables,viewport:{scrollAndZoomIntoView(){}},listAvailableFontsAsync:async()=>fonts.map(fontName=>({fontName})),loadFontAsync:async font=>assert(fonts.some(f=>f.family===font.family&&f.style===font.style)),createPage(){const p=new Node('PAGE');root.appendChild(p);return p;},async setCurrentPageAsync(p){assert.equal(p.type,'PAGE');currentPage=p;},createSection:()=>create('SECTION'),createFrame:()=>create('FRAME'),createComponent:()=>create('COMPONENT'),createText:()=>create('TEXT'),combineAsVariants,createTextStyle(){const style={id:'style:'+ ++sequence};styleMap.set(style.id,style);return style;},all};
}
const figma=model();const report=await buildFigma(figma,{colors,screens,tabs});
assert.equal(report.frameCount,screens.length*2);assert.equal(report.imageCount,0);assert(report.instanceCount>100);assert(report.textCount>100);
assert.equal(report.componentSetCount,4);
const cloudFontReport=await buildFigma(model([{family:'Noto Sans SC',style:'Regular'},{family:'Noto Sans SC',style:'Bold'}]),{colors,screens,tabs});
assert.equal(cloudFontReport.font,'Noto Sans SC');assert.equal(cloudFontReport.layoutAudit.issues.length,0);
const existingBlank=model();const blank=existingBlank.createPage();await existingBlank.setCurrentPageAsync(blank);
const blankReport=await buildFigma(existingBlank,{colors,screens,tabs});assert.equal(blankReport.reusedEmptyPage,true);assert.equal(existingBlank.root.children.length,1);
assert.deepEqual(blankReport.mutatedNodeIds,[blank.id]);assert(!blankReport.createdNodeIds.includes(blank.id));
assert.equal(report.layoutAudit.checkedFrames,screens.length*2);assert.equal(report.layoutAudit.issues.length,0);
assert(report.frames.every(f=>f.width===(f.mobile?390:1440)&&f.height>=844));
const horizontal=[...figma.all.values()].filter(n=>n.layoutMode==='HORIZONTAL');
assert(horizontal.every(n=>n.height>=18),'横排高度不应被固定到 1px');
const overflow=[];
for(const frame of report.frames){
 const root=figma.all.get(frame.id);const rb=root.absoluteBoundingBox;
 for(const node of root.findAll(()=>true)){
  let visible=true;for(let current=node;current&&current!==root;current=current.parent)if(!current.visible)visible=false;
  if(!visible)continue;
  const b=node.absoluteBoundingBox;
  if(b.x<rb.x-1||b.x+b.width>rb.x+rb.width+1||b.y<rb.y-1||b.y+b.height>rb.y+rb.height+1)overflow.push({screen:frame.screen,mobile:frame.mobile,node:node.name});
 }
}
assert.equal(overflow.length,0,'原生布局模型发现画板越界：'+JSON.stringify(overflow.slice(0,8)));
const ids=new Set(report.createdNodeIds);assert.equal(ids.size,report.createdNodeIds.length);
assert.equal(ids.size,figma.currentPage.findAll(()=>true).length+1,'报告应包含实例内部的全部节点');
await assert.rejects(()=>buildFigma(figma,{colors,screens,tabs}),/同名设计页/);
await assert.rejects(()=>buildFigma(model([{family:'Inter',style:'Regular'}]),{colors,screens,tabs}),/缺少产品中文字体/);
const testReport={status:'local-model-passed',actualFigmaVerified:false,frameCount:report.frameCount,componentCount:report.componentCount,componentSetCount:report.componentSetCount,instanceCount:report.instanceCount,textCount:report.textCount,simulatedNodeCount:report.createdNodeIds.length,localModelOverflow:overflow,checks:['all screen pairs generated','native editable text and instances','four variant families with shared text properties','Noto Sans SC fallback supported','no image fills','horizontal height hugs content','frame bounds contain visible descendants','font mismatch rejected','duplicate run preserves existing design','all created IDs reported','prototype targets exist']};
await writeFile(new URL('../docs/figma/complete/audit/builder-model-report.json',import.meta.url),JSON.stringify(testReport,null,2));
console.log(JSON.stringify(testReport));
