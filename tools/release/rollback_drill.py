"""Rollback drill: build and execute a safe rollback plan (dry-run by default)."""
import argparse
import json
import subprocess
import sys


def build_plan(current: dict, previous: dict, namespace: str) -> dict:
    """Compare two release manifests and produce a rollback plan."""
    for key in ["source", "image"]:
        if key not in current or key not in previous:
            raise ValueError(f"manifest missing '{key}' section")
    for sub in ["commit", "digest", "ref"]:
        if sub == "commit":
            if "commit" not in current.get("source", {}):
                raise ValueError("current manifest missing source.commit")
            if "commit" not in previous.get("source", {}):
                raise ValueError("previous manifest missing source.commit")
        else:
            if sub not in current.get("image", {}):
                raise ValueError(f"current manifest missing image.{sub}")
            if sub not in previous.get("image", {}):
                raise ValueError(f"previous manifest missing image.{sub}")

    cur_digest = current["image"]["digest"]
    prev_digest = previous["image"]["digest"]
    if cur_digest == prev_digest:
        raise ValueError("current and previous digests are identical; nothing to roll back")

    cur_mig = current.get("migrations", {})
    prev_mig = previous.get("migrations", {})
    mig_diff = (
        cur_mig.get("count") != prev_mig.get("count")
        or cur_mig.get("treeSha256") != prev_mig.get("treeSha256")
    )
    database_action = "MANUAL_REVIEW_REQUIRED" if mig_diff else "NONE"

    commands = [
        ["kubectl", "-n", namespace, "set", "image",
         "deployment/amz-gateway",
         f"amz-gateway={previous['image']['ref']}@{prev_digest}"],
    ]

    plan = {
        "namespace": namespace,
        "currentDigest": cur_digest,
        "previousDigest": prev_digest,
        "databaseAction": database_action,
        "applyBlocked": mig_diff,
        "commands": commands,
    }
    return plan


def execute_plan(plan: dict, apply: bool = False, context: str = "") -> None:
    """Execute or dry-run a rollback plan. Dry-run prints JSON lines only."""
    if apply:
        if plan.get("applyBlocked"):
            raise ValueError("plan is blocked (migration diff); manual review required")
        if not context:
            raise ValueError("kubernetes context is empty; refusing to apply")
        for cmd in plan["commands"]:
            full = ["kubectl", "--context", context] + cmd[1:]
            subprocess.run(full, check=True, capture_output=True)
            print(json.dumps({"event": "executed", "command": full[0:6]}))
    else:
        for cmd in plan["commands"]:
            print(json.dumps({"event": "dry-run", "command": cmd}))


def main():
    parser = argparse.ArgumentParser(description="Rollback drill")
    sub = parser.add_subparsers(dest="action")
    p_plan = sub.add_parser("plan")
    p_plan.add_argument("--current", required=True)
    p_plan.add_argument("--previous", required=True)
    p_plan.add_argument("--namespace", required=True)
    p_plan.add_argument("--output", required=True)
    p_exec = sub.add_parser("execute")
    p_exec.add_argument("--plan", required=True)
    p_exec.add_argument("--apply", action="store_true")
    p_exec.add_argument("--context", default="")
    args = parser.parse_args()

    if args.action == "plan":
        with open(args.current) as f:
            cur = json.load(f)
        with open(args.previous) as f:
            prev = json.load(f)
        plan = build_plan(cur, prev, args.namespace)
        with open(args.output, "w") as f:
            json.dump(plan, f, indent=2)
        print(f"Plan written to {args.output}")
    elif args.action == "execute":
        with open(args.plan) as f:
            plan = json.load(f)
        try:
            execute_plan(plan, apply=args.apply, context=args.context)
        except ValueError as e:
            print(f"ERROR: {e}", file=sys.stderr)
            sys.exit(2)


if __name__ == "__main__":
    main()
