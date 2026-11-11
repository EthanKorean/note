#!/usr/bin/env python3
"""도로명주소 한글 전체분을 내려받아 하나의 텍스트 파일로 합친다.

흐름: zip 다운로드 → 압축 해제 → 지역별 txt 파일을 하나로 병합 → 임시 파일 삭제.

zip 안에는 구조가 다른 두 종류의 파일이 들어 있다.
  - rnaddrkor_<지역>.txt        도로명주소 본체 (병합 대상)
  - jibun_rnaddrkor_<지역>.txt  관련 지번 (컬럼 구조가 달라 제외)
최종적으로 `result/<기준월>_juso.txt` 하나만 남는다.

사용법:
    python3 generate_juso.py                  # 기본: 2026년 10월 고시분(202610)
    python3 generate_juso.py --stdde 202611   # 다른 기준월
    python3 generate_juso.py --force          # 결과 파일이 이미 있어도 다시 생성

외부 패키지 없이 표준 라이브러리만 사용한다.
"""

import argparse
import shutil
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

BASE_URL = "https://business.juso.go.kr/api/jst/download"
CHUNK = 1024 * 1024  # 1MB
BASE_DIR = Path(__file__).resolve().parent
OUT_DIR = BASE_DIR / "result"  # 결과 파일 위치
EXCLUDE_PREFIX = "jibun_"  # 관련 지번 파일은 스키마가 달라 병합에서 제외


def build_url(stdde: str) -> str:
    """기준월(YYYYMM)로 다운로드 URL을 만든다."""
    params = {
        "regYmd": stdde[:4],
        "reqType": "ALLRNADR_KOR",
        "ctprvnCd": "00",
        "stdde": stdde,
        "fileName": f"{stdde}_도로명주소 한글_전체분.zip",
        "realFileName": f"RNADDR_KOR_{stdde[2:]}.zip",
        "intFileNo": "0",
        "intNum": "0",
    }
    return f"{BASE_URL}?{urllib.parse.urlencode(params)}"


def download(url: str, dest: Path) -> None:
    print(f"[1/3] 다운로드: {dest.name}")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    start = time.time()
    done = 0
    with urllib.request.urlopen(req, timeout=60) as res, open(dest, "wb") as f:
        total = int(res.headers.get("Content-Length") or 0)
        while chunk := res.read(CHUNK):
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r      {done / 1e6:7.1f} / {total / 1e6:.1f} MB ({done * 100 // total}%)", end="", flush=True)
    print(f"\n      완료 ({time.time() - start:.0f}초)")
    if not zipfile.is_zipfile(dest):
        raise RuntimeError("내려받은 파일이 zip이 아닙니다. 기준월이 맞는지, 사이트 응답을 확인하세요.")


def extract(zip_path: Path, target: Path) -> list[Path]:
    print("[2/3] 압축 해제")
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            # zip 안 파일명이 CP437로 해석되어 깨지는 경우를 복구한다.
            if not info.flag_bits & 0x800:
                try:
                    info.filename = info.filename.encode("cp437").decode("cp949")
                except (UnicodeEncodeError, UnicodeDecodeError):
                    pass
        zf.extractall(target)
    txts = [p for p in target.rglob("*") if p.is_file() and p.suffix.lower() == ".txt"]
    files = sorted(p for p in txts if not p.name.startswith(EXCLUDE_PREFIX))
    if not files:
        raise RuntimeError("압축 안에 병합할 txt 파일이 없습니다.")
    print(f"      txt {len(txts)}개 중 {len(files)}개 병합 ({EXCLUDE_PREFIX}* {len(txts) - len(files)}개 제외)")
    return files


def merge(files: list[Path], out_path: Path) -> int:
    """바이트 그대로 이어 붙인다(원본 인코딩 유지). 파일 끝에 개행이 없으면 보충한다."""
    print(f"[3/3] 병합: {out_path.name}")
    lines = 0
    with open(out_path, "wb") as out:
        for p in files:
            last = b"\n"
            file_lines = 0
            with open(p, "rb") as src:
                while chunk := src.read(CHUNK):
                    out.write(chunk)
                    file_lines += chunk.count(b"\n")
                    last = chunk[-1:]
            if last != b"\n":
                out.write(b"\n")
                file_lines += 1
            lines += file_lines
            print(f"      + {p.name:<34} {file_lines:>10,}건")
    return lines


def count_lines(path: Path) -> int:
    n = 0
    last = b"\n"
    with open(path, "rb") as f:
        while chunk := f.read(CHUNK):
            n += chunk.count(b"\n")
            last = chunk[-1:]
    return n + (0 if last == b"\n" else 1)


def main() -> int:
    ap = argparse.ArgumentParser(description="도로명주소 전체분 다운로드/병합")
    ap.add_argument("--stdde", default="202610", help="기준월 YYYYMM (기본 202610)")
    ap.add_argument("--force", action="store_true", help="결과 파일이 있어도 다시 생성")
    args = ap.parse_args()

    if len(args.stdde) != 6 or not args.stdde.isdigit():
        ap.error("--stdde는 YYYYMM 형식이어야 합니다.")

    OUT_DIR.mkdir(exist_ok=True)
    out_path = OUT_DIR / f"{args.stdde}_juso.txt"
    if out_path.exists() and not args.force:
        print(f"이미 존재합니다: {out_path} (다시 만들려면 --force)")
        print(f"총 {count_lines(out_path):,}건")
        return 0

    # 임시 파일은 작업 후 무조건 지운다(실패해도 남기지 않음).
    work = Path(tempfile.mkdtemp(prefix=".work_", dir=BASE_DIR))
    tmp_out = out_path.with_suffix(".txt.part")
    try:
        zip_path = work / "juso.zip"
        download(build_url(args.stdde), zip_path)
        files = extract(zip_path, work / "unzipped")
        lines = merge(files, tmp_out)
        tmp_out.replace(out_path)
    except Exception as e:
        print(f"\n실패: {e}", file=sys.stderr)
        return 1
    finally:
        shutil.rmtree(work, ignore_errors=True)
        tmp_out.unlink(missing_ok=True)

    print(f"\n완료: {out_path} ({out_path.stat().st_size / 1e6:.0f} MB)")
    print(f"총 {lines:,}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
