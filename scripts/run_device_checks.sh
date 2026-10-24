#!/usr/bin/env bash
# Keep emulator failures reviewable in public Actions annotations and the run summary.
set -uo pipefail
logfile="${RUNNER_TEMP:-/tmp}/tgdrive-device-checks.log"
./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest 2>&1 | tee "$logfile"
gradle_status=${PIPESTATUS[0]}
python3 - "$logfile" "$gradle_status" <<'PY'
import glob,os,re,sys,xml.etree.ElementTree as E
logfile,status=sys.argv[1],int(sys.argv[2])
text=open(logfile,errors='replace').read()
text=re.sub(r'\x1b\[[0-9;]*m','',text)
reports=[]
for path in glob.glob('app/build/outputs/androidTest-results/connected/**/*.xml',recursive=True):
 try: reports.append(E.parse(path).getroot())
 except E.ParseError: continue
failures=[]
for root in reports:
 for test in root.iter('testcase'):
  for child in test:
   if child.tag in ('failure','error'):
    failures.append(f"{test.get('classname')}.{test.get('name')}: {child.get('message','')}\n{child.text or ''}"[:6000])
selected=[line for line in text.splitlines() if re.search(r'FAILED|Exception|Caused by|Execution failed|No devices|Error:|Could not|FAILURE:|AssertionError|Fatal',line)]
problem='\n\n'.join(failures) or '\n'.join(selected[-35:]) or '\n'.join(text.splitlines()[-50:])
summary=os.environ.get('GITHUB_STEP_SUMMARY')
if summary:
 with open(summary,'a') as f:
  f.write('## Android device checks\n\n')
  f.write(f'Gradle exit code: {status}. XML reports found: {len(reports)}.\n\n')
  if status: f.write('```text\n'+problem[:24000]+'\n```\n')
  else:
   cases=[t for r in reports for t in r.iter('testcase')]
   f.write(f'Executed test cases: {len(cases)}.\n')
if status:
 def escape(s):return s.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
 print('::error title=Android device diagnostics::'+escape(problem[:24000]))
PY
exit "$gradle_status"
