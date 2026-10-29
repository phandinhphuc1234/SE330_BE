"""Private read-only S3 checksum snapshot using existing in-container credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path.cwd()))


def snapshot():
    from app.core.config import get_settings
    from app.documents.object_storage import S3CompatibleStorageAdapter
    settings = get_settings()
    client = S3CompatibleStorageAdapter().client
    rows = []
    total = 0
    for bucket in sorted({settings.library_ebook_bucket, settings.library_temp_bucket, settings.rag_artifact_bucket}):
        for page in client.get_paginator("list_objects_v2").paginate(Bucket=bucket):
            for item in page.get("Contents", []):
                total += item["Size"]
                if total > 32 * 1024 * 1024 or len(rows) >= 1000:
                    raise ValueError("Reviewed small-corpus object bounds exceeded")
                result = client.get_object(Bucket=bucket, Key=item["Key"])
                with result["Body"] as stream:
                    checksum = hashlib.sha256(stream.read()).hexdigest()
                rows.append({"bucket": bucket, "key": item["Key"], "size": item["Size"], "sha256": checksum})
    if not rows or not any(row["sha256"] == "73eb52598379f4c6799e85a73e02eb2cc18535fa29f534fe484ac12cbe91bd26" for row in rows):
        raise ValueError("Known PostgreSQL source PDF missing or different")
    return {"objects": sorted(rows, key=lambda row: (row["bucket"], row["key"])), "totalBytes": total}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    try:
        data = snapshot()
        if args.compare and json.loads(args.compare.read_text()) != data:
            raise ValueError("S3 object set, size or bytes changed")
        with args.output.open("x", encoding="utf-8") as target:
            json.dump(data, target, indent=2)
        print(json.dumps({"objects": len(data["objects"]), "totalBytes": data["totalBytes"],
                          "compared": args.compare is not None, "postgresPdfSha256Verified": True}))
    except Exception as error:
        print(json.dumps({"status": "FAILED", "errorType": type(error).__name__}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
