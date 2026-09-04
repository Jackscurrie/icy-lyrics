from pathlib import Path
import argparse
import datetime
import json
import subprocess
import sys
from source_fingerprint import fingerprint

ROOT = Path(__file__).resolve().parents[1]

def validate_summary(summary, simulator):
    """Validate the modern xcresulttool test-results summary, rejecting partial runs."""
    if not isinstance(summary,dict) or summary.get("result") != "Passed":
        raise ValueError("Simulator test result was not a pass; refusing verification marker.")
    for name in ("passedTests","failedTests","skippedTests","expectedFailures","totalTestCount"):
        value=summary.get(name,0 if name=="expectedFailures" else None)
        if type(value) is not int or value<0: raise ValueError(f"Invalid simulator summary count: {name}")
    if summary["passedTests"]<1 or summary["passedTests"]!=summary["totalTestCount"]:
        raise ValueError("Simulator tests were empty or incomplete.")
    if summary["failedTests"] or summary["skippedTests"] or summary.get("expectedFailures",0) or summary.get("testFailures"):
        raise ValueError("Simulator tests contain failures or skipped tests.")
    configurations=summary.get("devicesAndConfigurations")
    if not isinstance(configurations,list) or not configurations:
        raise ValueError("Simulator summary has no device information.")
    for configuration in configurations:
        device=configuration.get("device",{})
        if device.get("deviceId","").casefold()!=simulator.casefold() or device.get("platform")!="iOS Simulator" or device.get("architecture")!="arm64":
            raise ValueError("Test summary does not match the selected arm64 iPhone simulator.")

def main(argv=None):
    parser=argparse.ArgumentParser()
    parser.add_argument("result")
    parser.add_argument("simulator")
    parser.add_argument("runtime")
    parser.add_argument("--source-fingerprint",required=True)
    args=parser.parse_args(argv)
    result=Path(args.result).resolve()
    if not result.is_relative_to((ROOT/"build").resolve()) or result.suffix!=".xcresult" or not result.is_dir():
        raise SystemExit("Expected this build's simulator result bundle under iOS/build.")
    marker=ROOT/"build/reports/simulator-verification.json"
    marker.unlink(missing_ok=True)
    summary=json.loads(subprocess.check_output(["xcrun","xcresulttool","get","test-results","summary","--format","json","--path",str(result)]))
    validate_summary(summary,args.simulator)
    if fingerprint()!=args.source_fingerprint:
        raise SystemExit("Sources changed during simulator testing; refusing verification marker.")
    sha=subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip()
    record={"result":"passed","commit":sha,"sourceFingerprint":args.source_fingerprint,"simulator":args.simulator,"runtime":args.runtime,
            "recordedUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"summary":summary,
            "physicalIPhoneValidation":"pending","visualParity":"pending comparison and review"}
    marker.parent.mkdir(parents=True,exist_ok=True)
    marker.write_text(json.dumps(record,indent=2)+"\n")

if __name__=="__main__": main()
