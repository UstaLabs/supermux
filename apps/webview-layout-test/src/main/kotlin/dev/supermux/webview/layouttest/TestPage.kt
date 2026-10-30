package dev.supermux.webview.layouttest

/**
 * The page the test loads, as `loadHTMLString` with base URL `https://layout-test.invalid/`.
 * - Flat [Palette.PAGE_CSS] with text in a 170 px left column only, and a fixed [Palette.BAND_CSS] band on every
 *   viewport edge: what the screenshot checks look for.
 * - Messages (JSON strings, [PageMessages]): painted, down, scroll (not while auto-scrolling), input, frames (rAF
 *   intervals every 500 ms) and layout (the text box).
 * - `startAutoScroll()` / `stopAutoScroll()` / `report()` are called by the scenario through `Native.evaluate`.
 */
object TestPage {
    private const val BAND = Palette.BAND_CSS_PX

    val html: String = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Layout test page</title>
<style>
html,body{margin:0;background:${Palette.PAGE_CSS};color:#fff;font:15px/1.5 -apple-system,system-ui,sans-serif}
#doc{height:6000px;position:relative}
#col{position:absolute;left:24px;top:24px;width:170px}
.band{position:fixed;background:${Palette.BAND_CSS};z-index:10}
#bt{left:0;top:0;right:0;height:${BAND}px}#bb{left:0;bottom:0;right:0;height:${BAND}px}
#bl{left:0;top:0;bottom:0;width:${BAND}px}#br{right:0;top:0;bottom:0;width:${BAND}px}
#ta{position:fixed;right:24px;top:40px;width:260px;height:80px;z-index:11;font:16px system-ui}
#s2{position:absolute;top:3000px;left:24px}
button{font:inherit}
</style></head><body>
<div class="band" id="bt"></div><div class="band" id="bb"></div><div class="band" id="bl"></div><div class="band" id="br"></div>
<textarea id="ta" aria-label="Page text box"></textarea>
<div id="doc"><div id="col"><h1>Layout test page</h1>
<p><button id="b">A page button</button></p><p><a href="#s2">Go to section 2</a></p><p id="lines"></p></div>
<h2 id="s2">Section 2</h2></div>
<script>
var n=0,auto=false,prev=0,iv=[],lastY=-1;
function post(o){try{window.webkit.messageHandlers.probe.postMessage(JSON.stringify(o))}catch(e){}}
document.getElementById('b').addEventListener('click',function(){this.textContent='Clicked '+(++n)});
var lines=document.getElementById('lines');
for(var i=0;i<300;i++){lines.appendChild(document.createTextNode('Line '+i));lines.appendChild(document.createElement('br'))}
addEventListener('pointerdown',function(e){post({t:'down',x:e.clientX,y:e.clientY,target:e.target.id||e.target.tagName})},true);
var ta=document.getElementById('ta');
ta.addEventListener('input',function(){post({t:'input',value:ta.value})});
addEventListener('scroll',function(){if(!auto&&Math.abs(scrollY-lastY)>=1){lastY=scrollY;post({t:'scroll',y:scrollY})}},{passive:true});
function startAutoScroll(){auto=true}
function stopAutoScroll(){auto=false}
function report(){post({t:'scroll',y:scrollY});post({t:'input',value:ta.value})}
function frame(ts){if(prev)iv.push(Math.round((ts-prev)*100)/100);prev=ts;
if(auto){var max=document.documentElement.scrollHeight-innerHeight;scrollTo(0,scrollY+2>max?0:scrollY+2)}
requestAnimationFrame(frame)}
requestAnimationFrame(frame);
setInterval(function(){if(iv.length){post({t:'frames',i:iv});iv=[]}},500);
function sendLayout(){var r=ta.getBoundingClientRect();post({t:'layout',ta:[r.left,r.top,r.width,r.height],vw:innerWidth,vh:innerHeight})}
addEventListener('resize',sendLayout);
addEventListener('load',function(){requestAnimationFrame(function(){requestAnimationFrame(function(){sendLayout();post({t:'painted'})})})});
</script></body></html>"""
}
