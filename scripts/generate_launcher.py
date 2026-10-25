#!/usr/bin/env python3
"""Generate native Aero Fold launcher vectors from docs/brand SVGs (stdlib only)."""
from pathlib import Path
import argparse
import copy
import math
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
SVG = '{http://www.w3.org/2000/svg}'
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
E.register_namespace('android', ANDROID)
E.register_namespace('aapt', AAPT)
# Adaptive layers are 108dp; the usual visible mask is 72dp. Keep the cloud
# about 72% of that visible width, rather than filling 72% of the whole layer.
ADAPTIVE_WIDTH_DP = 52.0
CLOUD_WIDTH = 283.0
CLOUD_CENTER = (257.5, 242.5)
ARTBOARD = 512.0


def a(name):
    return '{' + ANDROID + '}' + name


def vector():
    return E.Element('vector', {a('width'): '108dp', a('height'): '108dp',
                               a('viewportWidth'): '512', a('viewportHeight'): '512'})


def convert(node, parent, definitions):
    kind = node.tag.removeprefix(SVG)
    if kind == 'g':
        assert 'transform' not in node.attrib, 'Sizing is applied explicitly below'
        out = E.SubElement(parent, 'group')
        if 'clip-path' in node.attrib:
            clip = definitions[node.attrib['clip-path'][5:-1]]
            for child in clip:
                E.SubElement(out, 'clip-path', {a('pathData'): child.attrib['d']})
        for child in node:
            convert(child, out, definitions)
        return
    if kind == 'path':
        data = node.attrib['d']
    elif kind == 'rect':
        width, height = node.attrib['width'], node.attrib['height']
        radius = float(node.get('rx', '0'))
        if radius:
            w, h = float(width), float(height)
            data = (f'M{radius} 0H{w-radius}Q{w} 0 {w} {radius}V{h-radius}'
                    f'Q{w} {h} {w-radius} {h}H{radius}Q0 {h} 0 {h-radius}'
                    f'V{radius}Q0 0 {radius} 0Z')
        else:
            data = f'M0 0H{width}V{height}H0Z'
    else:
        raise ValueError(f'Unsupported SVG geometry: {kind}')
    out = E.SubElement(parent, 'path', {a('pathData'): data})
    for svg_name, android_name in [('stroke', 'strokeColor'), ('stroke-width', 'strokeWidth'),
                                   ('stroke-opacity', 'strokeAlpha')]:
        if svg_name in node.attrib:
            out.set(a(android_name), node.attrib[svg_name])
    fill = node.get('fill', 'black')
    if fill == 'none':
        out.set(a('fillColor'), '#00000000')
    elif fill.startswith('url(#'):
        gradient = definitions[fill[5:-1]]
        assert gradient.tag == SVG+'linearGradient'
        assert gradient.get('gradientUnits') == 'userSpaceOnUse'
        attr = E.SubElement(out, '{'+AAPT+'}attr', {'name': 'android:fillColor'})
        attrs = {a('type'): 'linear'}
        for coord, target in [('x1', 'startX'), ('y1', 'startY'), ('x2', 'endX'), ('y2', 'endY')]:
            attrs[a(target)] = gradient.attrib[coord]
        native = E.SubElement(attr, 'gradient', attrs)
        for stop in gradient:
            E.SubElement(native, 'item', {a('offset'): stop.get('offset', '0'),
                                         a('color'): stop.attrib['stop-color']})
    else:
        out.set(a('fillColor'), fill)


def generate():
    svg = E.parse(ROOT/'docs/brand/tg-drive-icon.svg').getroot()
    definitions = {node.attrib['id']: node for node in svg.find(SVG+'defs')}
    logo = copy.deepcopy(next(node for node in svg if node.get('id') == 'logo'))
    assert logo.attrib.pop('transform') == 'translate(256 256) scale(1.3) translate(-257.5 -242.5)'
    background = next(node for node in svg if node.get('id') == 'background')
    scale = ADAPTIVE_WIDTH_DP / 108 * ARTBOARD / CLOUD_WIDTH
    # Even the cloud's enclosing rectangle fits inside the 66dp safe circle.
    assert math.hypot(ADAPTIVE_WIDTH_DP, 189/CLOUD_WIDTH*ADAPTIVE_WIDTH_DP) < 66
    result = {}
    for name in ['foreground', 'background', 'monochrome', 'legacy']:
        v = vector()
        if name in ['background', 'legacy']:
            bg = copy.deepcopy(background)
            if name == 'background':
                for rect in bg.iter(SVG+'rect'):
                    rect.attrib.pop('rx', None)
            convert(bg, v, definitions)
        if name != 'background':
            s = 1.3 if name == 'legacy' else scale
            group = E.SubElement(v, 'group', {
                a('scaleX'): f'{s:.9f}', a('scaleY'): f'{s:.9f}',
                a('translateX'): f'{256-s*CLOUD_CENTER[0]:.9f}',
                a('translateY'): f'{256-s*CLOUD_CENTER[1]:.9f}'})
            if name == 'monochrome':
                # A single cloud silhouette: no colored fold/shading to obscure system tint.
                E.SubElement(group, 'path', {a('fillColor'): '#FFFFFF',
                                           a('pathData'): next(logo.iter(SVG+'path')).attrib['d']})
            else:
                convert(logo, group, definitions)
        E.indent(v)
        path = ('mipmap-anydpi/ic_launcher.xml' if name == 'legacy'
                else f'drawable/ic_launcher_{name}.xml')
        result[ROOT/'app/src/main/res'/path] = E.tostring(v, encoding='utf-8', xml_declaration=True)+b'\n'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if generated resources differ')
    args = parser.parse_args()
    for path, content in generate().items():
        if args.check:
            if path.read_bytes() != content:
                raise SystemExit(f'Out of date: {path.relative_to(ROOT)}')
        else:
            path.write_bytes(content)
    print('Aero Fold launcher resources verified.' if args.check else 'Generated Aero Fold launcher resources.')


if __name__ == '__main__':
    main()
