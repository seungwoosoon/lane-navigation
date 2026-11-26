"""정밀도로지도 Shapefile 묶음(국토지리정보원, EPSG:5179) → DB에 추가하는 SQL 파일 만들기.

새 시험 지역을 DB에 넣을 때 쓴다. 이미 있는 테이블(a2_link 등)에 행을 '추가'하며,
같은 id가 이미 있으면 건너뛰므로 여러 번 실행해도 된다.

    py -m pip install pyshp
    py naviserver/sql/tools/hdmap_to_sql.py <zip 파일 또는 shp 폴더> -o hdmap_section.sql
    psql -U postgres -d lanenavi -f hdmap_section.sql
    psql -U postgres -d lanenavi -f naviserver/sql/01_route_graph.sql   (길찾기 그래프 다시 만들기)

- 레이어 이름은 파일 이름 끝의 a1_node, a2_link ... c6_postpoint 로 알아낸다.
- 원본 DBF의 gid 열은 src_gid 로 바꿔 넣는다 (DB의 gid 기본키와 겹치지 않게).
- 기존 테이블의 열 이름·형식·좌표 차원(2D/3D)에 맞춰 넣는다. 테이블이 없으면 새로 만든다.
"""
import argparse
import io
import logging
import re
import sys
import warnings
import zipfile
from pathlib import Path

import shapefile  # pyshp

# 고리 방향이 어긋난 면은 pyshp가 경고만 하고 그대로 읽는다 (서버는 면 레이어를 쓰지 않음)
logging.getLogger("shapefile").setLevel(logging.ERROR)
warnings.filterwarnings("ignore", module="shapefile")

LAYERS = ("a1_node", "a2_link", "a3_drivewaysection", "a4_subsidiarysection", "b1_safetysign",
          "b2_surfacelinemark", "b3_surfacemark", "c1_trafficlight", "c3_vehicleprotectionsafety",
          "c6_postpoint")
LAYER_RE = re.compile(r"(" + "|".join(LAYERS) + r")\.(shp|shx|dbf|prj|cpg)$", re.IGNORECASE)
BATCH = 300


def read_sources(src: Path) -> dict:
    """{layer: {ext: bytes}} from a zip (Korean file names in CP949) or a folder."""
    files = {}
    if src.is_file() and src.suffix.lower() == ".zip":
        with zipfile.ZipFile(src) as z:
            for info in z.infolist():
                name = info.filename
                if not info.flag_bits & 0x800:
                    name = name.encode("cp437").decode("cp949", "replace")
                m = LAYER_RE.search(name)
                if m:
                    files.setdefault(m.group(1).lower(), {})[m.group(2).lower()] = z.read(info)
    else:
        for p in src.rglob("*"):
            m = LAYER_RE.search(p.name)
            if m and p.is_file():
                files.setdefault(m.group(1).lower(), {})[m.group(2).lower()] = p.read_bytes()
    return files


def q(value) -> str:
    if value is None or value == "":
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return repr(value)
    return "'" + str(value).replace("'", "''") + "'"


def fmt(v: float) -> str:
    return f"{v:.3f}".rstrip("0").rstrip(".")


def wkt(shape) -> str | None:
    t = shape.shapeType
    if t == shapefile.NULL:
        return None
    pts = shape.points
    zs = list(getattr(shape, "z", []) or [])
    has_z = len(zs) == len(pts) and len(pts) > 0
    def p(i):
        x, y = pts[i]
        return f"{fmt(x)} {fmt(y)} {fmt(zs[i])}" if has_z else f"{fmt(x)} {fmt(y)}"
    zt = " Z" if has_z else ""
    if t in (shapefile.POINT, shapefile.POINTZ, shapefile.POINTM):
        return f"POINT{zt} ({p(0)})"
    if t in (shapefile.MULTIPOINT, shapefile.MULTIPOINTZ, shapefile.MULTIPOINTM):
        return f"MULTIPOINT{zt} (" + ", ".join(f"({p(i)})" for i in range(len(pts))) + ")"
    if t in (shapefile.POLYLINE, shapefile.POLYLINEZ, shapefile.POLYLINEM):
        bounds = list(shape.parts) + [len(pts)]
        lines = ["(" + ", ".join(p(i) for i in range(bounds[k], bounds[k + 1])) + ")"
                 for k in range(len(shape.parts)) if bounds[k + 1] - bounds[k] >= 2]
        return f"MULTILINESTRING{zt} (" + ", ".join(lines) + ")" if lines else None
    # 면(폴리곤): 바깥/안쪽 고리 정리는 pyshp에 맡기고 2D로 넣는다 (높이는 쓰지 않음)
    g = shape.__geo_interface__
    polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
    body = ", ".join("(" + ", ".join("(" + ", ".join(f"{fmt(x)} {fmt(y)}" for x, y, *_ in ring) + ")"
                                     for ring in poly) + ")" for poly in polys)
    return f"MULTIPOLYGON ({body})"


def pg_type(field) -> str:
    name, kind, size, dec = field
    if kind == "N":
        return "integer" if dec == 0 and size < 10 else "numeric"
    if kind == "F":
        return "double precision"
    if kind == "L":
        return "boolean"
    return f"varchar({max(size, 1)})"


def layer_sql(layer: str, parts: dict, out: io.TextIOBase) -> int:
    enc = (parts.get("cpg") or b"euckr").decode("ascii", "ignore").strip() or "euckr"
    enc = {"euckr": "cp949", "euc-kr": "cp949", "utf8": "utf-8"}.get(enc.lower(), enc)
    r = shapefile.Reader(shp=io.BytesIO(parts["shp"]), shx=io.BytesIO(parts["shx"]),
                         dbf=io.BytesIO(parts["dbf"]), encoding=enc, encodingErrors="replace")
    fields = [f for f in r.fields[1:]]
    names = [("src_gid" if f[0].lower() == "gid" else f[0].lower()) for f in fields]
    cols = ",\n  ".join(f"{n} {pg_type(f)}" for n, f in zip(names, fields))
    out.write(f"\n-- {layer}: {len(r)} rows\n")
    out.write(f"CREATE TABLE hdmap_import.{layer} (\n  {cols},\n  geom geometry\n);\n")
    collist = ", ".join(names + ["geom"])
    rows = []
    count = 0
    for sr in r.iterShapeRecords():
        g = wkt(sr.shape)
        vals = [q(v) for v in sr.record]
        vals.append("NULL" if g is None else f"ST_GeomFromText('{g}', 5179)")
        rows.append("(" + ", ".join(vals) + ")")
        count += 1
        if len(rows) == BATCH:
            out.write(f"INSERT INTO hdmap_import.{layer} ({collist}) VALUES\n" + ",\n".join(rows) + ";\n")
            rows = []
    if rows:
        out.write(f"INSERT INTO hdmap_import.{layer} ({collist}) VALUES\n" + ",\n".join(rows) + ";\n")
    return count


MERGE = r"""
-- 임시 스키마의 행을 public 테이블로 옮긴다 (기존 열·형식·좌표 차원에 맞춤, 같은 id는 건너뜀)
DO $$
DECLARE
    l text;
    gcol text;
    gtype text;
    gdims int;
    cols text;
    sel text;
    src text;
    added bigint;
BEGIN
    FOR l IN SELECT table_name FROM information_schema.tables WHERE table_schema = 'hdmap_import' ORDER BY 1 LOOP
        IF to_regclass('public.' || l) IS NULL THEN
            EXECUTE format('CREATE TABLE public.%I AS SELECT * FROM hdmap_import.%I WITH NO DATA', l, l);
            EXECUTE format('ALTER TABLE public.%I DROP COLUMN IF EXISTS src_gid', l);
            EXECUTE format('ALTER TABLE public.%I ADD COLUMN gid serial PRIMARY KEY', l);
            EXECUTE format('ALTER TABLE public.%I ALTER COLUMN geom TYPE geometry(Geometry, 5179) USING geom', l);
            EXECUTE format('CREATE INDEX ON public.%I USING gist (geom)', l);
            RAISE NOTICE 'created table public.%', l;
        END IF;

        SELECT f_geometry_column, upper(type), coord_dimension INTO gcol, gtype, gdims
        FROM geometry_columns WHERE f_table_schema = 'public' AND f_table_name = l LIMIT 1;
        IF gcol IS NULL THEN
            RAISE NOTICE 'skip %: no geometry column', l;
            CONTINUE;
        END IF;

        SELECT string_agg(quote_ident(c.attname), ', ' ORDER BY c.attnum),
               string_agg(format('CAST(s.%I AS %s)', c.attname, format_type(c.atttypid, c.atttypmod)), ', ' ORDER BY c.attnum)
          INTO cols, sel
        FROM pg_attribute c
        WHERE c.attrelid = ('public.' || quote_ident(l))::regclass AND c.attnum > 0 AND NOT c.attisdropped
          AND c.attname NOT IN ('gid', gcol)
          AND EXISTS (SELECT 1 FROM information_schema.columns s
                      WHERE s.table_schema = 'hdmap_import' AND s.table_name = l AND s.column_name = c.attname);

        src := 's.geom';
        IF gdims = 2 THEN src := format('ST_Force2D(%s)', src);
        ELSIF gdims = 3 THEN src := format('ST_Force3DZ(%s)', src);
        END IF;
        IF gtype LIKE 'MULTI%' THEN src := format('ST_Multi(%s)', src);
        ELSIF gtype IN ('POINT', 'LINESTRING', 'POLYGON') THEN src := format('ST_GeometryN(%s, 1)', src);
        END IF;

        EXECUTE format(
            'INSERT INTO public.%I (%s, %I) SELECT %s, %s FROM hdmap_import.%I s '
            'WHERE NOT EXISTS (SELECT 1 FROM public.%I t WHERE t.id = s.id)',
            l, cols, gcol, sel, src, l, l);
        GET DIAGNOSTICS added = ROW_COUNT;
        RAISE NOTICE '%: % rows added', l, added;
        EXECUTE format('ANALYZE public.%I', l);
    END LOOP;
END $$;

DROP SCHEMA hdmap_import CASCADE;
COMMIT;
-- 다음: naviserver/sql/01_route_graph.sql 을 실행해 길찾기 그래프(route_vertex, route_edge)를 다시 만든다.
"""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path, help="정밀도로지도 zip 파일 또는 shp 폴더")
    ap.add_argument("-o", "--output", type=Path, required=True)
    args = ap.parse_args()
    files = read_sources(args.source)
    missing = [l for l in files if not {"shp", "shx", "dbf"} <= set(files[l])]
    if not files or missing:
        print(f"레이어 파일을 찾지 못했습니다: {missing or args.source}", file=sys.stderr)
        return 1
    with args.output.open("w", encoding="utf-8", newline="\n") as out:
        out.write(f"-- 정밀도로지도 추가: {args.source.name}\n-- 만든 도구: naviserver/sql/tools/hdmap_to_sql.py\n")
        out.write("SET client_encoding TO 'UTF8';\nBEGIN;\nDROP SCHEMA IF EXISTS hdmap_import CASCADE;\nCREATE SCHEMA hdmap_import;\n")
        for layer in LAYERS:
            if layer in files:
                n = layer_sql(layer, files[layer], out)
                print(f"{layer}: {n}")
        out.write(MERGE)
    print(f"저장: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
