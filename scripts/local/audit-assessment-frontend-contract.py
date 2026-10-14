#!/usr/bin/env python3
"""Read-only baseline OpenAPI/controller/Gateway inventory audit; no service calls."""

from pathlib import Path
import re
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "e5c4ef3500f1ce40303af161cd6ca74b706c21b2"


def source(path):
    return subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=ROOT).decode()


def canonical(path):
    return re.sub(r"\{[^}]+\}", "{}", path)


contract = yaml.safe_load(source("services/assessment-service/contracts/publications.openapi.yaml"))
operations = {(method.upper(), path) for path, methods in contract["paths"].items()
              for method in methods if method in {"get", "post", "put", "patch", "delete"}}
controllers = set()
for name in ["PublicationController", "StudentAttemptController", "ClosureController"]:
    text = source("services/assessment-service/src/main/java/com/quizopia/assessment/api/" + name + ".java")
    base = re.search(r'@RequestMapping\("([^"]+)"\)', text).group(1)
    for method, suffix in re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"]*)"\))?', text):
        controllers.add((method.upper(), canonical(base + suffix)))
assert {(method, canonical(path)) for method, path in operations} == controllers
assert len(operations) == 24

with tempfile.TemporaryDirectory(prefix="quizopia-assessment-route-audit-") as temporary:
    directory = Path(temporary)
    java_sources = []
    for name in ["AdmissionRoutes", "PublicationRoutes", "StudentAttemptRoutes", "ClosureRoutes"]:
        path = directory / (name + ".java")
        path.write_text(source("gateway/src/main/java/com/quizopia/gateway/security/admission/" + name + ".java"))
        java_sources.append(str(path))
    probe = directory / "RouteProbe.java"
    probe.write_text('''import com.quizopia.gateway.security.admission.AdmissionRoutes;
public class RouteProbe {
  public static void main(String[] args) {
    for (int i=0; i<args.length; i+=2) {
      String role=AdmissionRoutes.requiredRole(args[i],args[i+1]);
      if (role==null) throw new AssertionError("Missing exact Gateway route");
      System.out.println(role);
    }
    if (AdmissionRoutes.requiredRole("POST","/internal/security/user-admissions")!=null)
      throw new AssertionError("Private route exposed");
  }
}''')
    subprocess.run(["javac", "-d", str(directory), *java_sources, str(probe)], check=True, capture_output=True)
    arguments = []
    ordered = sorted(operations)
    for method, path in ordered:
        concrete = re.sub(r"\{[^}]+\}", "00000000-0000-4000-8000-000000000001", path)
        concrete = concrete.replace("/questions/00000000-0000-4000-8000-000000000001/", "/questions/1/")
        arguments += [method, concrete]
    roles = subprocess.check_output(["java", "-cp", str(directory), "RouteProbe", *arguments], text=True).splitlines()
    assert len(roles) == 24 and all(role in {"TEACHER", "STUDENT"} for role in roles)
print("PASS baseline", BASELINE, "24 OpenAPI operations = 24 controller operations = 24 exact Gateway role adapters; private route excluded")
