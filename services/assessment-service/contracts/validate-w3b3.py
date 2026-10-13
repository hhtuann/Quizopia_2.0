#!/usr/bin/env python3
"""Read-only B3 contract audit. Uses existing PyYAML, jsonschema and openapi-spec-validator tooling."""
from pathlib import Path
import yaml, re, subprocess, os
from openapi_spec_validator import validate_spec
from jsonschema import Draft202012Validator
root = Path(__file__).resolve().parents[3]
os.chdir(root)
baseline = '5db5d41008ded12c8abdd27a2307d77f43dfdbd8'
files = ['services/assessment-service/contracts/publications.openapi.yaml', 'services/identity-service/contracts/user-admission.openapi.yaml', 'docs/specifications/proposed/w3b-w3c.openapi.yaml']
documents = {name: yaml.safe_load(Path(name).read_text()) for name in files}
for name, doc in documents.items():
    validate_spec(doc)

    def refs(value):
        if isinstance(value, dict):
            if '$ref' in value:
                target = value['$ref']
                assert target.startswith('#/'), (name, target)
                node = doc
                for key in target[2:].split('/'):
                    node = node[key.replace('~1', '/').replace('~0', '~')]
            for x in value.values():
                refs(x)
        elif isinstance(value, list):
            for x in value:
                refs(x)
    refs(doc)
    print('PASS OpenAPI structure and local refs:', name)
a = documents[files[0]]
i = documents[files[1]]
p = documents[files[2]]
original = yaml.safe_load(subprocess.check_output(['git', 'show', baseline + ':' + files[0]], text=True))
for path, operations in original['paths'].items():
    for method, operation in operations.items():
        assert a['paths'][path][method] == operation, (path, method)
for name, schema in original['components']['schemas'].items():
    assert a['components']['schemas'][name] == schema, name
print('PASS retained W3-A/B2 operations and schemas unchanged')
methods = {'get', 'post', 'put', 'patch', 'delete'}
assert sum((m in methods for v in a['paths'].values() for m in v)) == 24
assert set(i['paths']) == {'/internal/security/user-admissions', '/internal/security/notice-recipients/{studentId}/eligibility'}
assert not any((path.startswith('/internal/') for path in a['paths']))
for path, value in p['paths'].items():
    for method, op in value.items():
        if method not in methods:
            continue
        assert 'IMPLEMENTED_' in op['x-contract-status'], (path, method)
        owner = i if path.startswith('/internal/') else a
        assert op['operationId'] == owner['paths'][path][method]['operationId']
        assert op.get('security', [{'userBearer': []}]) == owner['paths'][path][method].get('security', [{'userBearer': []}])
for name in ['TeacherCommand', 'TeacherResultRow', 'TeacherResults', 'ClosedPublication', 'ClosureView', 'ClosureCompletion', 'Notification', 'NotificationPage', 'AttemptClosure']:
    assert a['components']['schemas'][name] == p['components']['schemas'][name], name
assert i['components']['schemas']['RecipientEligibility'] == p['components']['schemas']['RecipientEligibility']
recipient = i['paths']['/internal/security/notice-recipients/{studentId}/eligibility']['post']
assert recipient['security'] == [{'serviceBearer': []}]
assert recipient['x-required-service-scope'] == 'identity.notice-recipient.read'
assert set(i['components']['schemas']['RecipientEligibility']['properties']) == {'studentId', 'eligible', 'checkedAt'}
print('PASS exact inventory, frozen DTO parity and least-privilege Identity schema')
components = a['components']

def accepted(answer):
    schema = {'$ref': '#/components/schemas/Answer', 'components': components}
    return not list(Draft202012Validator(schema).iter_errors(answer))
positives = [{'type': 'SINGLE_CHOICE', 'optionLabel': 'A'}, {'type': 'MULTIPLE_CHOICE', 'optionLabels': ['A', 'C']}, {'type': 'TRUE_FALSE_MATRIX', 'judgments': dict(A=True, B=None, C=False, D=None)}, {'type': 'NUMERIC_FILL', 'value': '0001'}, {'type': 'NUMERIC_FILL', 'value': '-.25'}]
negatives = [{'type': 'SINGLE_CHOICE', 'optionLabel': 'E'}, {'type': 'MULTIPLE_CHOICE', 'optionLabels': ['A', 'A']}, {'type': 'TRUE_FALSE_MATRIX', 'judgments': dict(A='true', B=None, C=False, D=None)}, {'type': 'NUMERIC_FILL', 'value': '1e03'}, {'type': 'NUMERIC_FILL', 'value': 1}, {'type': 'NUMERIC_FILL', 'value': '0001', 'recipientId': 'spoof'}]
for answer in positives:
    assert accepted(answer), answer
for answer in negatives:
    assert not accepted(answer), answer
print('PASS 11 answer-schema acceptance/rejection fixtures')
protected = ['docs/decisions/ADR-015-w3b-w3c-leader-policy.md'] + [str(path) for path in Path('services/assessment-service/src/main/resources/db/migration').glob('*.sql') if re.match('V(?:1|2|3|4|4_1)__', path.name)]
for name in protected:
    old = subprocess.check_output(['git', 'show', baseline + ':' + name])
    assert old == Path(name).read_bytes(), name
print('PASS historical ADR-015 and V1-V4.1 byte preservation:', len(protected), 'files')
changed = set(subprocess.check_output(['git', 'diff', '--name-only', baseline], text=True).splitlines()) | set(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True).splitlines())
assert all((name.startswith(('gateway/', 'services/assessment-service/', 'services/identity-service/', 'docs/')) for name in changed))
assert not any((name.startswith('frontend/') or Path(name).name == '.env' for name in changed))
failures = []
for name in sorted(changed):
    if not name.endswith('.md'):
        continue
    content = Path(name).read_text()
    for target in re.findall('(?<!!)\\[[^\\]]*\\]\\(([^)]+)\\)', content):
        target = target.strip('<>')
        if re.match('^(https?://|mailto:|app:)', target):
            continue
        file, _, anchor = target.partition('#')
        destination = (Path(name).parent / file).resolve() if file else Path(name).resolve()
        if not destination.exists():
            failures.append((name, target))
        elif anchor and destination.suffix == '.md':
            headings = []
            for heading in re.findall('^#{1,6}\\s+(.+)$', destination.read_text(), re.M):
                slug = re.sub('[^\\w\\- ]', '', heading.lower()).replace(' ', '-')
                headings.append(slug)
            if anchor not in headings:
                failures.append((name, target))
assert not failures, failures
print('PASS changed-scope local Markdown links/anchors and authorized file scope:', len(changed), 'paths')
findings = []
for name in sorted(changed):
    content = Path(name).read_text()
    for line_no, line in enumerate(content.splitlines(), 1):
        if re.search('-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ghp_|github_pat_|sk-proj-)[A-Za-z0-9_]{20,}', line):
            findings.append((name, line_no))
assert not findings, findings
print('PASS changed-scope private-key/token credential scan')
