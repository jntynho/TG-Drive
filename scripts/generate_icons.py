#!/usr/bin/env python3
"""Generate the curated Lucide vectors from the checked-in, licensed SVG source."""
from pathlib import Path
import json,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[1]
def path_for(el):
 t=el.tag.split('}')[-1];a=el.attrib
 if t=='path':return a['d']
 if t in ('polyline','polygon'):return 'M'+a['points']+('Z' if t=='polygon' else '')
 if t=='line':return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
 if t in ('circle','ellipse'):
  x=float(a.get('cx',0));y=float(a.get('cy',0));rx=float(a.get('r',a.get('rx',0)));ry=float(a.get('r',a.get('ry',0)))
  return f'M{x-rx},{y}a{rx},{ry} 0 1 0 {2*rx},0a{rx},{ry} 0 1 0 {-2*rx},0'
 if t=='rect':
  x=float(a.get('x',0));y=float(a.get('y',0));w=float(a['width']);h=float(a['height']);r=float(a.get('rx',0))
  if r:return f'M{x+r},{y}h{w-2*r}a{r},{r} 0 0 1 {r},{r}v{h-2*r}a{r},{r} 0 0 1 {-r},{r}h{-w+2*r}a{r},{r} 0 0 1 {-r},{-r}v{-h+2*r}a{r},{r} 0 0 1 {r},{-r}Z'
  return f'M{x},{y}h{w}v{h}h{-w}Z'
 raise ValueError(t)
out=['package app.tgdrive.ui\n\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.SolidColor\nimport androidx.compose.ui.graphics.StrokeCap\nimport androidx.compose.ui.graphics.StrokeJoin\nimport androidx.compose.ui.graphics.vector.ImageVector\nimport androidx.compose.ui.graphics.vector.PathParser\nimport androidx.compose.ui.unit.dp\n\n/** Curated Lucide vectors; see third_party/lucide for source and license. */\nobject DriveIcons {']
for name,icon in json.loads((root/'third_party/lucide/aliases.json').read_text()).items():
 paths=[path_for(e) for e in E.parse(root/'third_party/lucide/icons'/f'{icon}.svg').getroot()]
 out.append(f'    val {name}: ImageVector by lazy {{ vector("{icon}", '+', '.join(json.dumps(p) for p in paths)+') }')
out.append('    private fun vector(name:String,vararg paths:String):ImageVector = ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {\n        paths.forEach { data -> addPath(PathParser().parsePathString(data).toNodes(),fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round) }\n    }.build()\n}\n')
(root/'app/src/main/java/app/tgdrive/ui/DriveIcons.kt').write_text('\n'.join(out))
