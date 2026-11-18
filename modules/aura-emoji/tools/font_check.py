"""Validate the font and exercise real HarfBuzz shaping using the system library."""
import ctypes as C
import ctypes.util
import io
from pathlib import Path
from fontTools.ttLib import TTFont
from PIL import Image

SAMPLES=['😀','❤️','👍🏽','👩\u200d💻','👨\u200d👩\u200d👧\u200d👦','🇬🇭','🏳️\u200d🌈','1️⃣','🧑🏾\u200d🚀']

def shape(path, text):
    h=C.CDLL(ctypes.util.find_library('harfbuzz'))
    def bind(name, result, args):
        f=getattr(h,name);f.restype=result;f.argtypes=args;return f
    ptr=C.c_void_p; u=C.c_uint; chars=C.c_char_p
    blob=bind('hb_blob_create_from_file_or_fail',ptr,[chars])(str(path).encode())
    if not blob: raise ValueError('HarfBuzz could not open font')
    face=bind('hb_face_create',ptr,[ptr,u])(blob,0)
    font=bind('hb_font_create',ptr,[ptr])(face)
    bind('hb_ot_font_set_funcs',None,[ptr])(font)
    buf=bind('hb_buffer_create',ptr,[])()
    data=text.encode()
    bind('hb_buffer_add_utf8',None,[ptr,chars,C.c_int,u,C.c_int])(buf,data,len(data),0,len(data))
    bind('hb_buffer_guess_segment_properties',None,[ptr])(buf)
    bind('hb_shape',None,[ptr,ptr,ptr,u])(font,buf,None,0)
    class Info(C.Structure):
        _fields_=[('codepoint',u),('mask',u),('cluster',u),('var1',u),('var2',u)]
    count=u()
    infos=bind('hb_buffer_get_glyph_infos',C.POINTER(Info),[ptr,C.POINTER(u)])(buf,C.byref(count))
    result=[infos[i].codepoint for i in range(count.value)]
    for name,obj in [('hb_buffer_destroy',buf),('hb_font_destroy',font),('hb_face_destroy',face),('hb_blob_destroy',blob)]:
        bind(name,None,[ptr])(obj)
    return result

def check(path):
    font=TTFont(path,checkChecksums=2)
    for table in ['CBDT','CBLC','GSUB','cmap','name']: assert table in font,table
    assert len(font.getGlyphOrder())>=3900
    assert len(font.getBestCmap())>=1400
    for table in font.keys():
        if table!='GlyphOrder': font[table] # Force table parsing, including layout and metadata.
    images=0
    for strike in font['CBDT'].strikeData:
        for glyph in strike.values():
            if hasattr(glyph,'imageData'):
                with Image.open(io.BytesIO(glyph.imageData)) as image: image.verify()
                images+=1
    assert images>=3900,images
    samples={text:shape(path,text) for text in SAMPLES}
    for text,ids in samples.items(): assert len(ids)==1 and ids[0]>0,(text,ids)
    return {'glyphs':len(font.getGlyphOrder()),'codepoints':len(font.getBestCmap()),'bitmap_glyphs':images,'samples':samples,'postscript':font['name'].getDebugName(6)}
