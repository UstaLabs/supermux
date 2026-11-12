package dev.supermux.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where an element is in the page's viewport, as fractions of the viewport's width and height (0 to 1 inside it), so a
 * host maps it onto the view whatever the page's zoom, a device mode's scale or the screen's density.
 */
public class PageBox(public val x: Float, public val y: Float, public val width: Float, public val height: Float) {
    override fun equals(other: Any?): Boolean =
        this === other || other is PageBox && x == other.x && y == other.y && width == other.width && height == other.height

    override fun hashCode(): Int = ((x.hashCode() * 31 + y.hashCode()) * 31 + width.hashCode()) * 31 + height.hashCode()

    override fun toString(): String = "PageBox(x=$x, y=$y, width=$width, height=$height)"
}

/**
 * An element a person pointed at (#122, pick and comment): a CSS [selector] that resolves back to it (as short as
 * stays unique: `aside.summary > button.buy`), its [tag], its visible [text] (the first 60 characters), a [label] to
 * show (`button.buy · 312×44`, its CSS size), and its [box] now.
 */
public class PageElement(
    public val selector: String,
    public val tag: String,
    public val text: String,
    public val label: String,
    public val box: PageBox,
) {
    override fun equals(other: Any?): Boolean = this === other || other is PageElement && selector == other.selector &&
        tag == other.tag && text == other.text && label == other.label && box == other.box

    override fun hashCode(): Int = selector.hashCode() * 31 + box.hashCode()

    override fun toString(): String = "PageElement(selector=$selector, tag=$tag, text=$text, label=$label, box=$box)"
}

/** The picker's scripts: read-only, run when asked, nothing left in the page. */
internal object PickScripts {
    /** The element at ([fx], [fy]) of the viewport, [up] ancestors above it; JSON or null. */
    fun elementAt(fx: Float, fy: Float, up: Int): String =
        "(function(){$COMMON var x=innerWidth*$fx,y=innerHeight*$fy;var el=document.elementFromPoint(x,y);" +
            "for(var i=0;i<$up&&el&&el.parentElement&&el.parentElement!==document.documentElement;i++)el=el.parentElement;" +
            "return el?describe(el):null;})()"

    /** Each selector's element box now, or null where it no longer resolves; a JSON array. */
    fun boxesOf(selectors: List<String>): String =
        "(function(){$COMMON var s=${Json.encodeToString(JsonArray.serializer(), JsonArray(selectors.map { kotlinx.serialization.json.JsonPrimitive(it) }))};" +
            "return s.map(function(q){var el=null;try{el=document.querySelector(q);}catch(e){}return el?box(el):null;});})()"

    /**
     * The picker's functions (#122): what a person pointed at, named as Chrome's "Copy selector" would, but meant to be
     * acted on: never a bare tag. Unique (querySelectorAll matches exactly that element) and stable: an id, then
     * data-testid / aria-label / name / placeholder / title, then meaningful classes (not generated ones:
     * `css-1x2y3z`, `Card_root__3xK9a`), and where the element alone doesn't say it, the nearest ancestor that does
     * (`section.cart > h3`), with :nth-of-type only when alike siblings need it. Kept plain JS: the test harness reads it.
     */
    internal const val PICK_JS = """
function esc(s){return window.CSS&&CSS.escape?CSS.escape(s):String(s).replace(/([^\w-])/g,function(m){return '\\'+m;});}
function quote(v){return "'"+String(v).replace(/\\/g,'\\\\').replace(/'/g,"\\'")+"'";}
function unique(q,el){try{var all=document.querySelectorAll(q);return all.length===1&&all[0]===el;}catch(e){return false;}}
function meaningful(c){return !!c&&c.length<30&&!/^(sc-|css-|jsx-|_)/.test(c)&&!/__[A-Za-z0-9]{4,}/.test(c)&&!/(?=[A-Za-z0-9]*[0-9])(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{5,}/.test(c);}
function classes(n){return (n.getAttribute('class')||'').trim().split(/\s+/).filter(meaningful).slice(0,2);}
var ATTRS=['data-testid','data-test','data-qa','data-cy','aria-label','name','placeholder','title','alt'];
function goodId(n){return n.id&&n.id.length<40&&!/^[0-9]/.test(n.id)&&!/[0-9]{4,}/.test(n.id)&&!/:/.test(n.id);}
function own(n){var t=n.nodeName.toLowerCase(),out=[];if(goodId(n))out.push('#'+esc(n.id));
for(var i=0;i<ATTRS.length;i++){var v=n.getAttribute(ATTRS[i]);if(v&&v.length<60)out.push(t+'['+ATTRS[i]+'='+quote(v)+']');}
var c=classes(n);if(c.length)out.push(t+'.'+c.map(esc).join('.'));return out;}
function nth(n){var p=n.parentElement;if(!p)return '';var t=n.nodeName,i=1,alike=0,k;for(k=p.firstElementChild;k;k=k.nextElementSibling){if(k.nodeName===t){alike++;if(k===n)i=alike;}}
return alike>1?':nth-of-type('+i+')':'';}
function path(el){var mine=own(el),i,t=el.nodeName.toLowerCase();
for(i=0;i<mine.length;i++)if(unique(mine[i],el))return mine[i];
var base=mine.length?mine[mine.length-1]:t;
var a=el.parentElement,direct=true;
while(a&&a!==document.body&&a!==document.documentElement){var anc=own(a);
for(var j=0;j<anc.length;j++){var sep=direct?' > ':' ';
var q=anc[j]+sep+base;if(unique(q,el))return q;
q=anc[j]+sep+base+nth(el);if(unique(q,el))return q;}
a=a.parentElement;direct=false;}
var parts=[],n=el;while(n&&n!==document.body&&n!==document.documentElement){var s=n===el?base:n.nodeName.toLowerCase();
parts.unshift(s+nth(n));var q2=parts.join(' > ');if((parts.length>1||mine.length)&&unique(q2,el))return q2;n=n.parentElement;}
return parts.join(' > ');}
function box(el){var r=el.getBoundingClientRect();return {x:r.left/innerWidth,y:r.top/innerHeight,w:r.width/innerWidth,h:r.height/innerHeight};}
function describe(el){var r=el.getBoundingClientRect();
var t=(el.innerText||el.textContent||el.value||el.getAttribute('aria-label')||el.getAttribute('alt')||'').replace(/\s+/g,' ').trim().slice(0,60);
var c=classes(el);
return {selector:path(el),tag:el.nodeName.toLowerCase(),text:t,label:el.nodeName.toLowerCase()+(c.length?'.'+c[0]:'')+' · '+Math.round(r.width)+'×'+Math.round(r.height),box:box(el)};}
"""

    private const val COMMON = PICK_JS

    fun parseElement(json: String?): PageElement? {
        val o = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull() ?: return null
        val box = parseBox(o["box"] as? JsonObject) ?: return null
        return PageElement(
            selector = o["selector"]?.jsonPrimitive?.contentOrNull ?: return null,
            tag = o["tag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            label = o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            box = box,
        )
    }

    fun parseBoxes(json: String?, count: Int): List<PageBox?> {
        val array = runCatching { Json.parseToJsonElement(json ?: "null").jsonArray }.getOrNull() ?: return List(count) { null }
        return List(count) { i -> (array.getOrNull(i) as? JsonObject)?.let(::parseBox) }
    }

    private fun parseBox(o: JsonObject?): PageBox? {
        o ?: return null
        fun f(k: String) = o[k]?.jsonPrimitive?.floatOrNull
        return PageBox(f("x") ?: return null, f("y") ?: return null, f("w") ?: return null, f("h") ?: return null)
    }
}
