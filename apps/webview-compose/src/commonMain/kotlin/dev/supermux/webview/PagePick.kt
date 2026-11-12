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

    // A path from the element up, each step tag[.two classes][:nth-of-type only when needed], stopping as soon as it is
    // unique in the document; verified to resolve back to the element (supercomment's rule), else the full path.
    private const val COMMON = "function esc(s){return window.CSS&&CSS.escape?CSS.escape(s):String(s).replace(/([^\\w-])/g,'\\\\\$1');}" +
        "function step(n){var s=n.nodeName.toLowerCase();if(n.id)return s+'#'+esc(n.id);" +
        "var c=(n.getAttribute('class')||'').trim().split(/\\s+/).filter(function(k){return k&&k.length<30&&!/^(sc-|css-)/.test(k);}).slice(0,2);" +
        "if(c.length)s+='.'+c.map(esc).join('.');var p=n.parentElement;if(p){var amb=true;try{amb=p.querySelectorAll(':scope > '+s).length>1;}catch(e){}" +
        "if(amb){var k=n,i=1;while((k=k.previousElementSibling))if(k.nodeName===n.nodeName)i++;s+=':nth-of-type('+i+')';}}return s;}" +
        "function unique(q,el){try{var all=document.querySelectorAll(q);return all.length===1&&all[0]===el;}catch(e){return false;}}" +
        "function path(el){var parts=[],n=el;while(n&&n.nodeType===1&&parts.length<8){var s=step(n);parts.unshift(s);" +
        "if(unique(parts.join(' > '),el))return parts.join(' > ');if(n.id)break;n=n.parentElement;}var q=parts.join(' > ');return q;}" +
        "function box(el){var r=el.getBoundingClientRect();return {x:r.left/innerWidth,y:r.top/innerHeight,w:r.width/innerWidth,h:r.height/innerHeight};}" +
        "function describe(el){var r=el.getBoundingClientRect();var t=(el.innerText||el.value||el.getAttribute('aria-label')||el.getAttribute('alt')||'').replace(/\\s+/g,' ').trim().slice(0,60);" +
        "var c=(el.getAttribute('class')||'').trim().split(/\\s+/).filter(function(k){return k;}).slice(0,1);" +
        "return {selector:path(el),tag:el.nodeName.toLowerCase(),text:t,label:el.nodeName.toLowerCase()+(c.length?'.'+c[0]:'')+' · '+Math.round(r.width)+'×'+Math.round(r.height),box:box(el)};}"

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
