/**
 * 原生 Figma 节点生成器。此模块也可由 MCP 调用；不依赖截图、SVG 文字或外部网络。
 * 正常开发插件负责生命周期，构建器只返回节点 ID 和验收证据。
 */
export async function buildFigma(figma, data, progress = () => {}) {
  const { screens, colors, tabs } = data;
  const stamp = 'Language Lean · 第一版完整界面';
  if (figma.root.children.some(p => p.name === stamp)) throw new Error('已存在同名设计页。请先重命名旧页再运行，避免覆盖你的修改。');
  const available = (await figma.listAvailableFontsAsync()).map(f => f.fontName);
  const families = ['Microsoft YaHei', 'Microsoft YaHei UI', 'PingFang SC', 'Noto Sans CJK SC'];
  const family = families.find(f => available.some(n => n.family === f));
  if (!family) throw new Error('缺少产品中文字体。请在 Figma 桌面端安装或启用 Microsoft YaHei，再运行插件。不会自动改用 Inter。');
  const font = available.find(f => f.family === family && /^(Regular|Normal|Book)$/.test(f.style)) || available.find(f => f.family === family);
  const bold = available.find(f => f.family === family && /^(Bold|Semibold|Semi Bold|DemiBold|Medium)$/.test(f.style)) || font;
  await Promise.all([figma.loadFontAsync(font), figma.loadFontAsync(bold)]);
  const created = [], variables = [], styles = [], links = [], frameMap = new Map();
  const track = node => { created.push(node.id); return node; };
  const page = track(figma.createPage()); page.name = stamp;
  await figma.setCurrentPageAsync(page);
  const collection = figma.variables.createVariableCollection('Language Lean / 第一版主题');
  collection.renameMode(collection.defaultModeId, '默认');
  const vars = {};
  const rgb = hex => ({r:parseInt(hex.slice(1,3),16)/255,g:parseInt(hex.slice(3,5),16)/255,b:parseInt(hex.slice(5,7),16)/255});
  // 基础值隐藏在 picker 外，语义色通过 alias 引用，范围覆盖实际用途。
  for (const [key, hex] of Object.entries(colors)) {
    const primitive = figma.variables.createVariable('primitive/'+key, collection, 'COLOR');
    primitive.scopes=[]; primitive.setValueForMode(collection.defaultModeId,rgb(hex)); variables.push(primitive.id);
    const semantic = figma.variables.createVariable('color/'+key, collection, 'COLOR');
    semantic.scopes=['FRAME_FILL','SHAPE_FILL','TEXT_FILL','STROKE_COLOR'];
    semantic.setValueForMode(collection.defaultModeId,{type:'VARIABLE_ALIAS',id:primitive.id}); variables.push(semantic.id);
    semantic.setVariableCodeSyntax('WEB', 'var(--ll-'+key+')'); vars[key]=semantic;
  }
  for (const value of [4,7,8,10,12,14,16,18,20,24,32,48,64]) {
    const spacing = figma.variables.createVariable('space/'+value,collection,'FLOAT');
    spacing.scopes=['GAP','CORNER_RADIUS']; spacing.setValueForMode(collection.defaultModeId,value);
    spacing.setVariableCodeSyntax('WEB','var(--ll-space-'+value+')'); vars['space'+value]=spacing; variables.push(spacing.id);
  }
  const paint = key => figma.variables.setBoundVariableForPaint({type:'SOLID',color:rgb(colors[key])},'color',vars[key]);
  const bind = (node,field,value) => { if(vars['space'+value]) node.setBoundVariable(field,vars['space'+value]); else node[field]=value; };
  const fill = (node,key) => { node.fills=[paint(key)]; };
  const border = (node,key='border') => { node.strokes=[paint(key)]; node.strokeWeight=1; };
  const roleSpec={body:[16,'text',false],muted:[14,'muted',false],label:[14,'label',false],small:[12,'muted',false],h2:[18,'text',true],h3:[16,'text',true],hero:[56,'text',true],title:[42,'text',true],error:[14,'error',false],warning:[14,'warning',false],tag:[13,'text',false],button:[16,'white',false]};
  const textStyles={}, pendingTextStyles=[];
  for(const [role,[size,key,isBold]] of Object.entries(roleSpec)){
    const style=figma.createTextStyle(); style.name='Language Lean/'+role; style.fontName=isBold?bold:font; style.fontSize=size; style.lineHeight={unit:'PERCENT',value:role==='hero'?130:160}; styles.push(style.id); textStyles[role]=style;
  }
  const makeText=(value,width,role='body',mobile=false)=>{
    const n=track(figma.createText()); n.name=role+' / '+value.slice(0,36); n.fontName=roleSpec[role][2]?bold:font;
    n.fontSize=roleSpec[role][0]; n.lineHeight={unit:'PERCENT',value:role==='hero'?130:160};
    n.characters=value; fill(n,roleSpec[role][1]);
    if(mobile&&(role==='hero'||role==='title')) n.fontSize=32;
    // dynamic-page 插件中 textStyleId 为只读，必须通过异步 API 应用。
    pendingTextStyles.push({node:n,styleId:textStyles[role].id,mobileSize:mobile&&(role==='hero'||role==='title')?32:null});
    n.textAutoResize='HEIGHT'; n.resize(Math.max(1,width),n.height); return n;
  };
  const applyTextStyles=async()=>{const batch=pendingTextStyles.splice(0);await Promise.all(batch.map(async({node,styleId,mobileSize})=>{await node.setTextStyleIdAsync(styleId);if(mobileSize)node.fontSize=mobileSize;}));};
  const container=(name,width,direction='VERTICAL',gap=18,padding=0,component=false)=>{
    // createFrame + layoutMode is supported by ordinary desktop plugins as well as MCP.
    const n=track(component?figma.createComponent():figma.createFrame()); n.name=name; n.fills=[];
    n.layoutMode=direction; n.clipsContent=false;
    n.resize(Math.max(1,width),1);
    // 横排固定宽度、高度自适应；竖排固定宽度、长度自适应。不能把横排高度固定成 1px。
    n.primaryAxisSizingMode=direction==='VERTICAL'?'AUTO':'FIXED';
    n.counterAxisSizingMode=direction==='VERTICAL'?'FIXED':'AUTO';
    bind(n,'itemSpacing',gap); for(const side of ['Top','Bottom','Left','Right']) bind(n,'padding'+side,padding);
    return n;
  };
  const append=(parent,child)=>{parent.appendChild(child);return child;};
  const expose=(component,node,label)=>{
    const key=component.addComponentProperty(label,'TEXT',node.characters);
    node.componentPropertyReferences={characters:key}; return key;
  };
  const comps={};
  // 与源代码重复控件对应的组件：按钮、输入框、列表项、复选框、标签。
  for(const tone of ['primary','secondary','quiet','disabled']){
    const c=container('Button/'+tone,200,'VERTICAL',4,10,true); c.description='操作按钮；通过文字属性修改标签。';
    fill(c,tone==='primary'||tone==='disabled'?'primary':tone==='secondary'?'secondary':'bg');
    if(tone==='secondary')border(c); bind(c,'cornerRadius',8); c.counterAxisAlignItems='CENTER';
    const label=append(c,makeText('按钮',180,'button')); fill(label,tone==='primary'||tone==='disabled'?'white':'primary'); label.textAlignHorizontal='CENTER';
    const key=expose(c,label,'文字'); if(tone==='disabled')c.opacity=.5;
    comps['button/'+tone]={node:c,keys:{text:key}};
  }
  for(const mode of ['input','textarea','select','password','number','file','disabled']){
    const c=container('Field/'+mode,300,'VERTICAL',7,0,true); c.description='有标签的表单控件，文字、值和说明均可编辑。';
    const label=append(c,makeText('字段名称',300,'label')); const labelKey=expose(c,label,'字段名称');
    const input=append(c,container('控件',300,'VERTICAL',4,10)); bind(input,'cornerRadius',7); border(input,'inputBorder'); fill(input,mode==='disabled'?'secondary':'white');
    const value=append(input,makeText('输入内容',280,'body')); const valueKey=expose(c,value,'值');
    if(mode==='textarea')input.minHeight=84; else input.minHeight=42;
    const note=append(c,makeText('字段说明',300,'small')); const noteKey=expose(c,note,'说明');
    comps['field/'+mode]={node:c,keys:{label:labelKey,value:valueKey,note:noteKey}};
  }
  for(const selected of [false,true]){
    const c=container('Entry/'+(selected?'selected':'default'),300,'VERTICAL',8,16,true); c.description='词条、单词本和配置选项的列表行。';
    fill(c,selected?'selected':'subcard');border(c,selected?'primary':'border');bind(c,'cornerRadius',10);
    const title=append(c,makeText('标题',268,'h3'));const sub=append(c,makeText('说明',268,'small'));
    comps['entry/'+selected]={node:c,keys:{title:expose(c,title,'标题'),subtitle:expose(c,sub,'说明')}};
  }
  const tag=container('Tag',180,'VERTICAL',4,4,true);fill(tag,'tag');bind(tag,'cornerRadius',7);
  const tagLabel=append(tag,makeText('状态',172,'tag'));comps.tag={node:tag,keys:{text:expose(tag,tagLabel,'文字')}};
  for(const checked of [true,false]){
    const c=container('Checkbox/'+checked,300,'HORIZONTAL',8,0,true);
    const box=append(c,container('复选框',18,'VERTICAL',0,0));border(box,'inputBorder');fill(box,checked?'primary':'white');box.resize(18,18);box.primaryAxisSizingMode='FIXED';
    const label=append(c,makeText('复选项',274,'label'));
    comps['check/'+checked]={node:c,keys:{text:expose(c,label,'文字')}};
  }
  await applyTextStyles();
  const library=track(figma.createSection());library.name='00 · 基础组件';page.appendChild(library);
  let cx=24,cy=24,rowHeight=0;
  for(const comp of Object.values(comps)){
    if(cx+comp.node.width>1500){cx=24;cy+=rowHeight+48;rowHeight=0;}
    library.appendChild(comp.node);comp.node.x=cx;comp.node.y=cy;cx+=comp.node.width+48;rowHeight=Math.max(rowHeight,comp.node.height);
  }
  library.resizeWithoutConstraints(1640,cy+rowHeight+24);
  function fit(instance,width){
    instance.resize(width,instance.height);
    instance.primaryAxisSizingMode=instance.layoutMode==='VERTICAL'?'AUTO':'FIXED';
    instance.counterAxisSizingMode=instance.layoutMode==='VERTICAL'?'FIXED':'AUTO';
    // 覆盖后的文字必须按新宽度换行，不能使用 width-and-height 自动撑宽。
    function walk(node,w){
      const inner=Math.max(1,w-(node.paddingLeft||0)-(node.paddingRight||0));
      for(const child of node.children||[]){
        if(child.type==='TEXT') {child.textAutoResize='HEIGHT';child.resize(node.layoutMode==='HORIZONTAL'?Math.max(1,inner-26):inner,child.height);}
        else if('layoutMode' in child&&child.name!=='复选框'){child.resize(inner,child.height);child.primaryAxisSizingMode=child.layoutMode==='VERTICAL'?'AUTO':'FIXED';child.counterAxisSizingMode=child.layoutMode==='VERTICAL'?'FIXED':'AUTO';walk(child,inner);}
      }
    } walk(instance,width);
  }
  const use=(key,values,width)=>{
    const c=comps[key];const n=track(c.node.createInstance());const props={};
    for(const [field,value] of Object.entries(values))props[c.keys[field]]=value;
    n.setProperties(props);fit(n,width);
    if(key.startsWith('field/')&&!values.note){const note=n.findAllWithCriteria({types:['TEXT']}).find(t=>t.name.startsWith('small /'));if(note)note.visible=false;}
    return n;
  };
  function render(spec,width,mobile){
    if(spec.kind==='text')return spec.role==='tag'?use('tag',{text:spec.text},width):makeText(spec.text,width,spec.role,mobile);
    if(spec.kind==='button'){
      const n=use('button/'+spec.tone,{text:spec.text},width);n.name='操作 / '+spec.text;
      if(spec.target)links.push({node:n,target:spec.target,mobile});return n;
    }
    if(spec.kind==='field')return use('field/'+spec.mode,{label:spec.label,value:spec.value||' ',note:spec.note},width);
    if(spec.kind==='entry'){
      const n=use('entry/'+spec.selected,{title:spec.title,subtitle:spec.subtitle},width);if(spec.target)links.push({node:n,target:spec.target,mobile});return n;
    }
    if(spec.kind==='check')return use('check/'+spec.checked,{text:spec.text},width);
    if(spec.kind==='table'){
      const table=container('数据表',width,'VERTICAL',0);const rows=[spec.headers,...spec.rows];
      for(let r=0;r<rows.length;r++){
        const row=container('表格行 '+r,width,'HORIZONTAL',0);fill(row,r===0?'secondary':'white');
        const cellWidth=width/spec.headers.length;
        for(const value of rows[r]){const cell=append(row,container('单元格',cellWidth,'VERTICAL',0,mobile?4:10));border(cell);append(cell,makeText(String(value),Math.max(1,cellWidth-(mobile?8:20)),r===0?'label':'small',mobile));}
        append(table,row);
      }return table;
    }
    const horizontal=!mobile&&(spec.kind==='row'||spec.kind==='actions');
    const padding=spec.kind==='card'?(mobile?20:24):0;
    const n=container(spec.kind==='card'?'卡片 / '+spec.title:spec.kind,width,horizontal?'HORIZONTAL':'VERTICAL',spec.kind==='actions'?12:18,padding);
    if(spec.kind==='card'){fill(n,'white');border(n);bind(n,'cornerRadius',16);append(n,makeText(spec.title,width-2*padding,'h2',mobile));}
    const inner=width-2*padding;
    // 后台按源码 330px 目录 + 可伸缩编辑列；移动端统一单列。
    const catalogLayout=horizontal&&spec.children[0]?.title==='词条目录';
    const childWidth=horizontal?(inner-(spec.kind==='actions'?12:18)*(spec.children.length-1))/spec.children.length:inner;
    spec.children.forEach((child,index)=>append(n,render(child,catalogLayout?(index===0?330:inner-348):childWidth,mobile)));
    return n;
  }
  let sectionY=library.height+160;const results=[];
  for(const group of [...new Set(screens.map(s=>s.group))]){
    const section=track(figma.createSection());section.name=group;page.appendChild(section);section.y=sectionY;
    let y=60,maxWidth=0;
    for(const s of screens.filter(s=>s.group===group)){
      let maxHeight=0;
      for(const mobile of [false,true]){
        // 弹窗尺寸在创建任何子节点前确定，品牌和导航也使用弹窗画板的实际内容宽度。
        const width=mobile?390:1440;const pagePadding=mobile?24:s.dialog?400:s.wide?60:s.id.startsWith('login')?500:340;
        const root=container(s.id+' / '+s.name+' / '+(mobile?'移动端':'桌面端'),width,'VERTICAL',24,pagePadding);
        // 左右 padding 与纵向独立。弹窗画板覆盖背景，弹窗本体为 auto-layout。
        bind(root,'paddingTop',mobile?24:s.wide?32:64);bind(root,'paddingBottom',48);fill(root,'bg');root.minHeight=mobile?844:900;
        section.appendChild(root);root.x=mobile?1540:40;root.y=y;frameMap.set(s.id+'/'+mobile,root);
        const inner=width-2*pagePadding;
        append(root,makeText('LANGUAGE LEAN'+(s.wide?' · 后台管理':''),inner,'small',mobile));
        if(!s.id.startsWith('login'))append(root,render({kind:'actions',children:[{kind:'button',text:'学习首页',tone:'quiet',target:'home'},{kind:'button',text:s.wide?'查看用户词典':'我的单词本',tone:'quiet',target:s.wide?'dictionary-list':'wordbooks'}]},inner,mobile));
        if(s.dialog){
          // 桌面居中 640px 弹窗；移动端 342px。背景采用纯色，不放入完整 UI 截图。
          const modal=container('弹窗 / '+s.name,width-2*root.paddingLeft,'VERTICAL',18,24);fill(modal,'white');border(modal);bind(modal,'cornerRadius',14);
          append(root,modal);const mw=modal.width-48;append(modal,makeText(s.name,mw,'h2',mobile));
          for(const child of s.children)append(modal,render(child,mw,mobile));
        }else{
          if(s.heading&&s.id!=='dictionary-list')append(root,makeText(s.heading,inner,s.wide?'title':'hero',mobile));
          if(s.wide&&s.route==='/admin')append(root,render({kind:'actions',children:tabs.map(([text,target])=>({kind:'button',text,tone:target===s.activeAdminTab?'primary':'secondary',target}))},inner,mobile));
          for(const child of s.children)append(root,render(child,inner,mobile));
        }
        const note=makeText(s.implemented?'已有实现 · '+(s.route||'应用内状态'):'第一版设计 · 待开发',width-48,'small',mobile);
        section.appendChild(note);note.x=root.x;note.y=y-32;
        await applyTextStyles();
        const nodes=root.findAll(()=>true);const types={};for(const n of nodes)types[n.type]=(types[n.type]||0)+1;
        results.push({screen:s.id,name:s.name,group:s.group,mobile,implemented:s.implemented,id:root.id,width:root.width,height:root.height,types});
        maxHeight=Math.max(maxHeight,root.height);maxWidth=Math.max(maxWidth,root.x+width+40);
      }
      progress({done:results.length,total:screens.length*2,screen:s.name});y+=maxHeight+100;
    }
    section.resizeWithoutConstraints(maxWidth,y);sectionY+=y+160;
  }
  for(const link of links){const destination=frameMap.get(link.target+'/'+link.mobile);if(destination)await link.node.setReactionsAsync([{trigger:{type:'ON_CLICK'},actions:[{type:'NODE',destinationId:destination.id,navigation:'NAVIGATE',transition:null,resetVideoPosition:false}]}]);}
  const all=page.findAll(()=>true);const imageNodes=all.filter(n=>Array.isArray(n.fills)&&n.fills.some(p=>p.type==='IMAGE'));
  const texts=all.filter(n=>n.type==='TEXT');const invalidFonts=texts.filter(n=>n.fontName===figma.mixed||n.fontName.family!==family);
  const unbounded=texts.filter(n=>n.width<=0||n.textAutoResize!=='HEIGHT');
  if(imageNodes.length||invalidFonts.length||unbounded.length)throw new Error('原生结构验收失败：图片 '+imageNodes.length+'，字体 '+invalidFonts.length+'，文字宽度 '+unbounded.length);
  // 实际 Figma 运行时读回全部画板的真实边界，不以本地模拟或 HTML 尺寸代替。
  const layoutIssues=[];let checkedVisibleNodes=0;
  for(const result of results){
    const frame=frameMap.get(result.screen+'/'+result.mobile);const frameBounds=frame.absoluteBoundingBox;
    if(!frameBounds){layoutIssues.push({screen:result.screen,mobile:result.mobile,reason:'missing-frame-bounds'});continue;}
    for(const node of frame.findAll(()=>true)){
      let visible=true;for(let current=node;current&&current!==frame;current=current.parent)if(!current.visible)visible=false;
      if(!visible)continue;
      const bounds=node.absoluteBoundingBox;checkedVisibleNodes++;
      if(!bounds||!Number.isFinite(bounds.width)||!Number.isFinite(bounds.height)||bounds.width<=0||bounds.height<=0){layoutIssues.push({screen:result.screen,mobile:result.mobile,nodeId:node.id,name:node.name,reason:'invalid-node-bounds'});continue;}
      if(bounds.x<frameBounds.x-1||bounds.y<frameBounds.y-1||bounds.x+bounds.width>frameBounds.x+frameBounds.width+1||bounds.y+bounds.height>frameBounds.y+frameBounds.height+1)layoutIssues.push({screen:result.screen,mobile:result.mobile,nodeId:node.id,name:node.name,reason:'outside-frame'});
    }
    result.width=frame.width;result.height=frame.height;
  }
  if(layoutIssues.length)throw new Error('实际画板存在 '+layoutIssues.length+' 个布局问题：'+JSON.stringify(layoutIssues.slice(0,6))+'。请保留该设计页用于排查，暂勿当作完成稿。');
  figma.currentPage.selection=[frameMap.get('home/false')];figma.viewport.scrollAndZoomIntoView(figma.currentPage.selection);
  return {status:'generated-awaiting-visual-review',pageId:page.id,font:family,fontStyles:[font.style,bold.style],screenCount:screens.length,frameCount:results.length,componentCount:Object.keys(comps).length,instanceCount:all.filter(n=>n.type==='INSTANCE').length,textCount:texts.length,imageCount:0,createdNodeIds:[page.id,...all.map(n=>n.id)],variableIds:variables,styleIds:styles,frames:results,layoutAudit:{checkedFrames:results.length,checkedVisibleNodes,issues:layoutIssues},visualReview:false};
}
