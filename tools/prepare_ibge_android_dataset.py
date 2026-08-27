#!/usr/bin/env python3
"""Build the bounded, read-only IBGE index shipped with the Android app.

The desktop cache cannot be bundled verbatim: it uses SQLite STRICT tables,
contains a build-machine path, and does not provide release-grade provenance.
This transformer validates the known local source, creates a normalized schema
compatible with Android API 23 framework SQLite, and emits a deterministic gzip
asset plus its integrity manifest.

The derived database intentionally preserves attributes and source envelopes in
a portable table, but does not invent sector polygons. Population-by-coverage
and exact point-in-polygon operations remain unavailable until a separate
geometry package exists.
"""

from __future__ import annotations

import argparse
from collections.abc import Callable, Iterable
from contextlib import closing
import gzip
import hashlib
import json
import math
import os
import re
import sqlite3
import tempfile
import unicodedata
from pathlib import Path
from typing import BinaryIO


ASSET_DATABASE_PREFIX = "ibge-census-sectors-2022-"
ASSET_DATABASE_SUFFIX = ".ibgedata"
MANIFEST_NAME = "manifest.json"
DATABASE_APPLICATION_ID = 0x41545849  # ASCII "ATXI"
DATABASE_SCHEMA = 1
MANIFEST_SCHEMA = 1
TRANSFORMER_VERSION = "1"

EXPECTED_SOURCE_INDEX_SHA256 = (
    "fe8b789027d54de02de5fd1ddac7c77325657ee09721672008cb6227009a91a7"
)
EXPECTED_SOURCE_ARCHIVE_SHA256 = (
    "2674870a37718df4418f93dcca7d6931783f7b03f59562de82c7402324350750"
)
EXPECTED_SOURCE_ARCHIVE_BYTES = 784_726_998
EXPECTED_SOURCE_SIGNATURE = (
    "01751dfb92b0b37a5b73f874b0f8a6e79165ab4242dd1a77e0cfc3526d2f2141"
)
EXPECTED_SECTOR_COUNT = 468_099
EXPECTED_VALID_MUNICIPALITY_COUNT = 5_570
EXPECTED_UNASSIGNED_SECTOR_COUNT = 2
EXPECTED_POPULATION_TOTAL = 203_080_756

DATASET_ID = "ibge-census-sectors-2022-brazil"
DATASET_TITLE = "IBGE 2022 Census Sector Index — Brazil"
PROVIDER = "Instituto Brasileiro de Geografia e Estatística (IBGE)"
SOURCE_URL = (
    "https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/"
    "malhas_de_setores_censitarios__divisoes_intramunicipais/censo_2022/"
    "setores/shp/BR/BR_setores_CD2022.zip"
)
SOURCE_PAGE_URL = (
    "https://www.ibge.gov.br/geociencias/organizacao-do-territorio/"
    "malhas-territoriais/26565-malhas-de-setores-censitarios-divisoes-"
    "intramunicipais.html"
)
ATTRIBUTION = "Source: IBGE — 2022 Census Sector Mesh and sector aggregates."
LICENSE_STATUS = (
    "Public IBGE download; the source archive contains no machine-readable license. "
    "Review the applicable IBGE terms before public redistribution."
)

STATE_NAMES: dict[str, tuple[str, str]] = {
    "11": ("RO", "Rondônia"),
    "12": ("AC", "Acre"),
    "13": ("AM", "Amazonas"),
    "14": ("RR", "Roraima"),
    "15": ("PA", "Pará"),
    "16": ("AP", "Amapá"),
    "17": ("TO", "Tocantins"),
    "21": ("MA", "Maranhão"),
    "22": ("PI", "Piauí"),
    "23": ("CE", "Ceará"),
    "24": ("RN", "Rio Grande do Norte"),
    "25": ("PB", "Paraíba"),
    "26": ("PE", "Pernambuco"),
    "27": ("AL", "Alagoas"),
    "28": ("SE", "Sergipe"),
    "29": ("BA", "Bahia"),
    "31": ("MG", "Minas Gerais"),
    "32": ("ES", "Espírito Santo"),
    "33": ("RJ", "Rio de Janeiro"),
    "35": ("SP", "São Paulo"),
    "41": ("PR", "Paraná"),
    "42": ("SC", "Santa Catarina"),
    "43": ("RS", "Rio Grande do Sul"),
    "50": ("MS", "Mato Grosso do Sul"),
    "51": ("MT", "Mato Grosso"),
    "52": ("GO", "Goiás"),
    "53": ("DF", "Distrito Federal"),
}

VALID_SHA256 = re.compile(r"^[0-9a-f]{64}$")
VALID_CODE = re.compile(r"^[0-9]+$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-index", required=True, type=Path)
    parser.add_argument("--source-archive", required=True, type=Path)
    parser.add_argument(
        "--output-directory",
        type=Path,
        default=Path("app/src/main/assets/datasets/ibge"),
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        copy_chunks(stream, digest.update)
    return digest.hexdigest()


def copy_chunks(stream: BinaryIO, consume: Callable[[bytes], object]) -> None:
    while chunk := stream.read(1024 * 1024):
        consume(chunk)


def search_key(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value)
    ascii_only = "".join(character for character in decomposed if not unicodedata.combining(character))
    return " ".join(ascii_only.casefold().split())


def source_connection(path: Path) -> sqlite3.Connection:
    uri = f"{path.resolve().as_uri()}?mode=ro&immutable=1"
    return sqlite3.connect(uri, uri=True)


def require_equal(actual: object, expected: object, description: str) -> None:
    if actual != expected:
        raise ValueError(f"{description} mismatch: expected {expected!r}, found {actual!r}.")


def validate_source(index_path: Path, archive_path: Path) -> dict[str, str]:
    if not index_path.is_file() or not archive_path.is_file():
        raise FileNotFoundError("The source index and official source archive must both exist.")
    require_equal(
        sha256_file(index_path),
        EXPECTED_SOURCE_INDEX_SHA256,
        "Source index SHA-256",
    )
    require_equal(archive_path.stat().st_size, EXPECTED_SOURCE_ARCHIVE_BYTES, "Source archive size")
    require_equal(
        sha256_file(archive_path),
        EXPECTED_SOURCE_ARCHIVE_SHA256,
        "Source archive SHA-256",
    )
    with closing(source_connection(index_path)) as connection:
        require_equal(connection.execute("PRAGMA integrity_check").fetchone()[0], "ok", "Source integrity")
        metadata = dict(connection.execute("SELECT key, value FROM metadata"))
        require_equal(metadata.get("index_schema"), "1", "Source schema")
        require_equal(metadata.get("source_signature"), EXPECTED_SOURCE_SIGNATURE, "Source signature")
        sector_count = int(connection.execute("SELECT count(*) FROM sector").fetchone()[0])
        bounds_count = int(connection.execute("SELECT count(*) FROM sector_bounds").fetchone()[0])
        require_equal(sector_count, EXPECTED_SECTOR_COUNT, "Source sector count")
        require_equal(bounds_count, EXPECTED_SECTOR_COUNT, "Source RTree count")
    return metadata


def create_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        f"""
        PRAGMA application_id={DATABASE_APPLICATION_ID};
        PRAGMA user_version={DATABASE_SCHEMA};
        PRAGMA page_size=4096;
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;

        CREATE TABLE metadata (
            key TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL
        );

        CREATE TABLE state (
            code TEXT PRIMARY KEY NOT NULL,
            abbreviation TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL
        );

        CREATE TABLE municipality (
            code TEXT PRIMARY KEY NOT NULL,
            state_code TEXT NOT NULL,
            name TEXT NOT NULL,
            search_name TEXT NOT NULL,
            sector_count INTEGER NOT NULL,
            urban_sector_count INTEGER NOT NULL,
            rural_sector_count INTEGER NOT NULL,
            unspecified_sector_count INTEGER NOT NULL,
            missing_population_sector_count INTEGER NOT NULL,
            population_total INTEGER NOT NULL,
            urban_population INTEGER NOT NULL,
            rural_population INTEGER NOT NULL,
            unspecified_population INTEGER NOT NULL,
            area_total_km2 REAL NOT NULL,
            urban_area_km2 REAL NOT NULL,
            rural_area_km2 REAL NOT NULL,
            unspecified_area_km2 REAL NOT NULL,
            west REAL NOT NULL,
            south REAL NOT NULL,
            east REAL NOT NULL,
            north REAL NOT NULL
        );

        CREATE TABLE sector (
            id INTEGER PRIMARY KEY NOT NULL,
            source_record_index INTEGER NOT NULL UNIQUE,
            sector_code TEXT NOT NULL UNIQUE,
            state_code TEXT NOT NULL,
            municipality_code TEXT,
            situation_code INTEGER NOT NULL,
            area_km2 REAL NOT NULL,
            population INTEGER
        );

        CREATE TABLE sector_bounds (
            id INTEGER PRIMARY KEY NOT NULL,
            min_longitude REAL NOT NULL,
            max_longitude REAL NOT NULL,
            min_latitude REAL NOT NULL,
            max_latitude REAL NOT NULL,
            CHECK(min_longitude <= max_longitude),
            CHECK(min_latitude <= max_latitude)
        );
        """
    )


def municipality_template(name: str, state_code: str) -> dict[str, object]:
    return {
        "name": name,
        "state_code": state_code,
        "sector_count": 0,
        "urban_sector_count": 0,
        "rural_sector_count": 0,
        "unspecified_sector_count": 0,
        "missing_population_sector_count": 0,
        "population_total": 0,
        "urban_population": 0,
        "rural_population": 0,
        "unspecified_population": 0,
        "area_total_km2": 0.0,
        "urban_area_km2": 0.0,
        "rural_area_km2": 0.0,
        "unspecified_area_km2": 0.0,
        "west": math.inf,
        "south": math.inf,
        "east": -math.inf,
        "north": -math.inf,
    }


def situation_code(source_value: str) -> int:
    if source_value == "Urbana":
        return 1
    if source_value == "Rural":
        return 2
    if not source_value:
        return 0
    raise ValueError(f"Unknown source situation value {source_value!r}.")


def population_value(source_value: object) -> int | None:
    if source_value is None:
        return None
    numeric = float(source_value)
    if not math.isfinite(numeric) or numeric < 0 or not numeric.is_integer():
        raise ValueError(f"Invalid sector population {source_value!r}.")
    return int(numeric)


def validate_bounds(west: float, south: float, east: float, north: float) -> None:
    values = (west, south, east, north)
    if not all(math.isfinite(value) for value in values):
        raise ValueError("A sector contains non-finite bounds.")
    if not (-180 <= west <= east <= 180 and -90 <= south <= north <= 90):
        raise ValueError(f"A sector contains invalid SIRGAS 2000 bounds: {values!r}.")


def update_municipality(
    aggregate: dict[str, object],
    *,
    situation: int,
    area_km2: float,
    population: int | None,
    west: float,
    south: float,
    east: float,
    north: float,
) -> None:
    aggregate["sector_count"] = int(aggregate["sector_count"]) + 1
    aggregate["area_total_km2"] = float(aggregate["area_total_km2"]) + area_km2
    aggregate["west"] = min(float(aggregate["west"]), west)
    aggregate["south"] = min(float(aggregate["south"]), south)
    aggregate["east"] = max(float(aggregate["east"]), east)
    aggregate["north"] = max(float(aggregate["north"]), north)
    prefix = {0: "unspecified", 1: "urban", 2: "rural"}[situation]
    count_key = f"{prefix}_sector_count"
    area_key = f"{prefix}_area_km2"
    population_key = f"{prefix}_population"
    aggregate[count_key] = int(aggregate[count_key]) + 1
    aggregate[area_key] = float(aggregate[area_key]) + area_km2
    if population is None:
        aggregate["missing_population_sector_count"] = (
            int(aggregate["missing_population_sector_count"]) + 1
        )
    else:
        aggregate["population_total"] = int(aggregate["population_total"]) + population
        aggregate[population_key] = int(aggregate[population_key]) + population


def copy_source_rows(
    source: sqlite3.Connection,
    destination: sqlite3.Connection,
) -> tuple[int, int, int, dict[str, dict[str, object]]]:
    municipalities: dict[str, dict[str, object]] = {}
    total_population = 0
    missing_population_count = 0
    unassigned_sector_count = 0
    sector_rows: list[tuple[object, ...]] = []
    bounds_rows: list[tuple[object, ...]] = []
    query = source.execute(
        """
        SELECT s.id, s.record_index, s.sector_code, s.situation, s.area_km2,
               s.state_code, s.municipality_code, s.municipality_name, s.population,
               b.minx, b.maxx, b.miny, b.maxy
        FROM sector AS s
        JOIN sector_bounds AS b ON b.id = s.id
        ORDER BY s.id
        """
    )
    copied = 0
    for row in query:
        (
            identifier,
            source_record_index,
            sector_code,
            source_situation,
            raw_area,
            state_code,
            raw_municipality_code,
            municipality_name,
            raw_population,
            west,
            east,
            south,
            north,
        ) = row
        identifier = int(identifier)
        source_record_index = int(source_record_index)
        sector_code = str(sector_code)
        state_code = str(state_code)
        municipality_name = str(municipality_name)
        area_km2 = float(raw_area)
        population = population_value(raw_population)
        situation = situation_code(str(source_situation))
        west, east, south, north = map(float, (west, east, south, north))
        if not sector_code or not VALID_CODE.fullmatch(sector_code):
            raise ValueError(f"Invalid sector code at source record {source_record_index}.")
        if state_code not in STATE_NAMES:
            raise ValueError(f"Unknown state code {state_code!r}.")
        if not math.isfinite(area_km2) or area_km2 < 0:
            raise ValueError(f"Invalid area for sector {sector_code}.")
        validate_bounds(west, south, east, north)
        municipality_code = str(raw_municipality_code)
        if not (len(municipality_code) == 7 and VALID_CODE.fullmatch(municipality_code)):
            municipality_code = None
            unassigned_sector_count += 1
        else:
            aggregate = municipalities.setdefault(
                municipality_code,
                municipality_template(municipality_name, state_code),
            )
            if aggregate["name"] != municipality_name or aggregate["state_code"] != state_code:
                raise ValueError(f"Municipality {municipality_code} has inconsistent identity fields.")
            update_municipality(
                aggregate,
                situation=situation,
                area_km2=area_km2,
                population=population,
                west=west,
                south=south,
                east=east,
                north=north,
            )
        if population is None:
            missing_population_count += 1
        else:
            total_population += population
        sector_rows.append(
            (
                identifier,
                source_record_index,
                sector_code,
                state_code,
                municipality_code,
                situation,
                area_km2,
                population,
            )
        )
        bounds_rows.append((identifier, west, east, south, north))
        if len(sector_rows) >= 5_000:
            insert_sector_batch(destination, sector_rows, bounds_rows)
            copied += len(sector_rows)
            sector_rows.clear()
            bounds_rows.clear()
    if sector_rows:
        insert_sector_batch(destination, sector_rows, bounds_rows)
        copied += len(sector_rows)
    return copied, total_population, missing_population_count, municipalities


def insert_sector_batch(
    connection: sqlite3.Connection,
    sectors: Iterable[tuple[object, ...]],
    bounds: Iterable[tuple[object, ...]],
) -> None:
    connection.executemany(
        """
        INSERT INTO sector(
            id, source_record_index, sector_code, state_code, municipality_code,
            situation_code, area_km2, population
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        sectors,
    )
    connection.executemany("INSERT INTO sector_bounds VALUES (?, ?, ?, ?, ?)", bounds)


def insert_municipalities(
    connection: sqlite3.Connection,
    municipalities: dict[str, dict[str, object]],
) -> None:
    rows = []
    for code, value in sorted(municipalities.items()):
        rows.append(
            (
                code,
                value["state_code"],
                value["name"],
                search_key(str(value["name"])),
                value["sector_count"],
                value["urban_sector_count"],
                value["rural_sector_count"],
                value["unspecified_sector_count"],
                value["missing_population_sector_count"],
                value["population_total"],
                value["urban_population"],
                value["rural_population"],
                value["unspecified_population"],
                value["area_total_km2"],
                value["urban_area_km2"],
                value["rural_area_km2"],
                value["unspecified_area_km2"],
                value["west"],
                value["south"],
                value["east"],
                value["north"],
            )
        )
    connection.executemany(
        """
        INSERT INTO municipality VALUES (
            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """,
        rows,
    )


def build_database(
    source_index: Path,
    destination: Path,
    source_metadata: dict[str, str],
) -> dict[str, int]:
    with closing(source_connection(source_index)) as source, closing(
        sqlite3.connect(destination)
    ) as output:
        create_schema(output)
        output.executemany(
            "INSERT INTO state(code, abbreviation, name) VALUES (?, ?, ?)",
            ((code, values[0], values[1]) for code, values in sorted(STATE_NAMES.items())),
        )
        copied, total_population, missing_population_count, municipalities = copy_source_rows(
            source,
            output,
        )
        require_equal(copied, EXPECTED_SECTOR_COUNT, "Derived sector count")
        require_equal(total_population, EXPECTED_POPULATION_TOTAL, "Derived population total")
        require_equal(
            len(municipalities),
            EXPECTED_VALID_MUNICIPALITY_COUNT,
            "Derived municipality count",
        )
        unassigned_sector_count = copied - sum(
            int(value["sector_count"]) for value in municipalities.values()
        )
        require_equal(
            unassigned_sector_count,
            EXPECTED_UNASSIGNED_SECTOR_COUNT,
            "Unassigned sector count",
        )
        insert_municipalities(output, municipalities)
        output.executescript(
            """
            CREATE INDEX municipality_search_name_idx ON municipality(search_name);
            CREATE INDEX municipality_state_idx ON municipality(state_code, name);
            CREATE INDEX sector_municipality_idx ON sector(municipality_code);
            """
        )
        metadata = {
            "attribution": ATTRIBUTION,
            "census_year": "2022",
            "database_schema": str(DATABASE_SCHEMA),
            "dataset_id": DATASET_ID,
            "dataset_title": DATASET_TITLE,
            "derived_from_indexed_at": source_metadata["indexed_at"],
            "geometry_included": "false",
            "license_status": LICENSE_STATUS,
            "municipality_count": str(len(municipalities)),
            "population_field": "v0001",
            "population_interpretation": "Resident population total by census sector",
            "population_missing_sector_count": str(missing_population_count),
            "population_total": str(total_population),
            "provider": PROVIDER,
            "sector_code_field": "CD_SETOR",
            "sector_count": str(copied),
            "source_accessed_on": "2026-08-27",
            "source_archive_bytes": str(EXPECTED_SOURCE_ARCHIVE_BYTES),
            "source_archive_name": "BR_setores_CD2022.zip",
            "source_archive_sha256": EXPECTED_SOURCE_ARCHIVE_SHA256,
            "source_crs": "EPSG:4674",
            "source_crs_name": "SIRGAS 2000 geographic",
            "source_file_date": "2024-11-12",
            "source_index_sha256": EXPECTED_SOURCE_INDEX_SHA256,
            "source_page_url": SOURCE_PAGE_URL,
            "source_signature": EXPECTED_SOURCE_SIGNATURE,
            "source_url": SOURCE_URL,
            "sector_bounds_storage": (
                "Portable SQLite table; bounding boxes only, with no spatial extension or polygon geometry"
            ),
            "transformer_version": TRANSFORMER_VERSION,
            "unassigned_sector_count": str(unassigned_sector_count),
        }
        output.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            sorted(metadata.items()),
        )
        output.commit()
        output.execute("VACUUM")
    return {
        "municipality_count": len(municipalities),
        "population_missing_sector_count": missing_population_count,
        "population_total": total_population,
        "sector_count": copied,
        "unassigned_sector_count": unassigned_sector_count,
    }


def validate_derived_database(path: Path, counts: dict[str, int]) -> None:
    with closing(source_connection(path)) as connection:
        require_equal(
            connection.execute("PRAGMA application_id").fetchone()[0],
            DATABASE_APPLICATION_ID,
            "Derived application ID",
        )
        require_equal(
            connection.execute("PRAGMA user_version").fetchone()[0],
            DATABASE_SCHEMA,
            "Derived database schema",
        )
        require_equal(connection.execute("PRAGMA integrity_check").fetchone()[0], "ok", "Derived integrity")
        require_equal(
            connection.execute("SELECT count(*) FROM sector").fetchone()[0],
            counts["sector_count"],
            "Derived sector table count",
        )
        require_equal(
            connection.execute("SELECT count(*) FROM sector_bounds").fetchone()[0],
            counts["sector_count"],
            "Derived sector bounds count",
        )
        require_equal(
            connection.execute("SELECT count(*) FROM municipality").fetchone()[0],
            counts["municipality_count"],
            "Derived municipality table count",
        )
        strict_tables = connection.execute(
            "SELECT count(*) FROM sqlite_master WHERE sql LIKE '% STRICT%'"
        ).fetchone()[0]
        require_equal(strict_tables, 0, "Android-incompatible STRICT table count")


def deterministic_gzip(source: Path, destination: Path) -> None:
    with source.open("rb") as input_stream, destination.open("wb") as output_stream:
        with gzip.GzipFile(filename="", mode="wb", fileobj=output_stream, mtime=0, compresslevel=9) as compressed:
            while chunk := input_stream.read(1024 * 1024):
                compressed.write(chunk)


def manifest_payload(
    database_path: Path,
    compressed_path: Path,
    compressed_asset_name: str,
    counts: dict[str, int],
) -> dict[str, object]:
    database_sha256 = sha256_file(database_path)
    compressed_sha256 = sha256_file(compressed_path)
    if not VALID_SHA256.fullmatch(database_sha256) or not VALID_SHA256.fullmatch(compressed_sha256):
        raise AssertionError("Generated SHA-256 has an invalid representation.")
    return {
        "manifestSchema": MANIFEST_SCHEMA,
        "databaseSchema": DATABASE_SCHEMA,
        "databaseApplicationId": DATABASE_APPLICATION_ID,
        "datasetId": DATASET_ID,
        "datasetTitle": DATASET_TITLE,
        "provider": PROVIDER,
        "censusYear": 2022,
        "sourceCrs": "EPSG:4674",
        "sourceCrsName": "SIRGAS 2000 geographic",
        "sourceUrl": SOURCE_URL,
        "sourcePageUrl": SOURCE_PAGE_URL,
        "sourceAccessedOn": "2026-08-27",
        "sourceArchiveName": "BR_setores_CD2022.zip",
        "sourceArchiveBytes": EXPECTED_SOURCE_ARCHIVE_BYTES,
        "sourceArchiveSha256": EXPECTED_SOURCE_ARCHIVE_SHA256,
        "sourceIndexSha256": EXPECTED_SOURCE_INDEX_SHA256,
        "sourceSignature": EXPECTED_SOURCE_SIGNATURE,
        "attribution": ATTRIBUTION,
        "licenseStatus": LICENSE_STATUS,
        "geometryIncluded": False,
        "sectorBoundsDescription": (
            "Portable SQLite table with sector bounding boxes; no spatial extension or polygon geometry"
        ),
        "populationField": "v0001",
        "sectorCount": counts["sector_count"],
        "municipalityCount": counts["municipality_count"],
        "unassignedSectorCount": counts["unassigned_sector_count"],
        "missingPopulationSectorCount": counts["population_missing_sector_count"],
        "populationTotal": counts["population_total"],
        "compressedAssetFile": compressed_asset_name,
        "compressedByteCount": compressed_path.stat().st_size,
        "compressedSha256": compressed_sha256,
        "databaseByteCount": database_path.stat().st_size,
        "databaseSha256": database_sha256,
        "transformerVersion": TRANSFORMER_VERSION,
    }


def main() -> int:
    args = parse_args()
    source_index = args.source_index.resolve()
    source_archive = args.source_archive.resolve()
    output_directory = args.output_directory.resolve()
    source_metadata = validate_source(source_index, source_archive)
    output_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=".atx-ibge-android-",
        dir=output_directory,
    ) as temporary:
        database_path = Path(temporary) / "ibge.sqlite"
        compressed_path = Path(temporary) / "payload.ibgedata"
        counts = build_database(source_index, database_path, source_metadata)
        validate_derived_database(database_path, counts)
        deterministic_gzip(database_path, compressed_path)
        compressed_sha256 = sha256_file(compressed_path)
        compressed_asset_name = (
            f"{ASSET_DATABASE_PREFIX}{compressed_sha256}{ASSET_DATABASE_SUFFIX}"
        )
        manifest = manifest_payload(
            database_path,
            compressed_path,
            compressed_asset_name,
            counts,
        )
        final_asset = output_directory / compressed_asset_name
        final_manifest = output_directory / MANIFEST_NAME
        os.replace(compressed_path, final_asset)
        staged_manifest = Path(temporary) / MANIFEST_NAME
        staged_manifest.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        os.replace(staged_manifest, final_manifest)
        for prior_asset in output_directory.glob(
            f"{ASSET_DATABASE_PREFIX}*{ASSET_DATABASE_SUFFIX}"
        ):
            if prior_asset != final_asset and prior_asset.is_file():
                prior_asset.unlink()
    print(f"Created {final_asset} ({final_asset.stat().st_size:,} bytes)")
    print(f"Created {final_manifest}")
    print(f"Database SHA-256: {manifest['databaseSha256']}")
    print(f"Compressed SHA-256: {manifest['compressedSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
