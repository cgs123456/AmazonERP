import subprocess, sys, os

java_bin = r"D:\Java\tools\jdk-21.0.11+10\bin\java.exe"
plexus = r"D:\maven\apache-maven-3.9.9\boot\plexus-classworlds-2.8.0.jar"
conf = r"D:\maven\apache-maven-3.9.9\bin\m2.conf"
repo = r"D:\Java\mvn-repository"
base = r"D:\Desktop\amazon-erp"

args = [
    java_bin,
    "-classpath", plexus,
    "-Dclassworlds.conf=" + conf,
    "-Dmaven.home=" + r"D:\maven\apache-maven-3.9.9",
    "-Dmaven.multiModuleProjectDirectory=" + base,
    "org.codehaus.plexus.classworlds.launcher.Launcher",
    "-pl", "amz-service/amz-service-ai",
]
if len(sys.argv) > 1 and sys.argv[1] == "test":
    args += ["test", "-Dtest=AgentEvalTest"]
else:
    args += ["compile"]
args += ["-Dmaven.repo.local=" + repo, "-q", "-o"]

env = os.environ.copy()
env["JAVA_TOOL_OPTIONS"] = ""
result = subprocess.run(args, capture_output=True, text=True, timeout=120, errors="replace", env=env)
stdout = result.stdout if result.stdout else ""
stderr = result.stderr if result.stderr else ""
print("STDOUT:", stdout[-3000:])
print("STDERR:", stderr[-3000:])
print("RC:", result.returncode)
