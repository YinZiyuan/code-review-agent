# Demo Script

## 1. Build

```bash
export APEMIND_API_KEY=<your-apemind-key>
mvn -q clean package -DskipTests
```

## 2. Review A Tiny Local Diff

```bash
tmpdir="$(mktemp -d)"
cd "$tmpdir"
git init -q
cat > UserService.java <<'JAVA'
class UserService {
    String displayName(User user) {
        return user.name();
    }
}
JAVA
git add UserService.java
git -c user.name=Demo -c user.email=demo@example.com commit -qm init
python3 - <<'PY'
from pathlib import Path
p = Path("UserService.java")
p.write_text(p.read_text().replace("return user.name();", "return user.getName().trim();"))
PY

cd /Users/yzy/Project/code-review-agent
java -jar target/code-review-agent-1.0.0.jar review "$tmpdir" HEAD
```

## 3. Run Smoke Eval

```bash
cd /Users/yzy/Project/code-review-agent
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version demo-smoke \
  --pipeline w3-pipeline \
  --suite smoke \
  --runs 1
```

## 4. Regenerate Metric Docs

```bash
scripts/plot_metrics.py
sed -n '1,80p' docs/eval-metrics.md
```

## Notes For Recording

- Start with the architecture diagram in `README.md`.
- Show that eval samples have `annotation.json`, then point out that the agent-visible boundary excludes it.
- Use `grep -c "review failed" /tmp/v3-run.log` after a release run when demonstrating the no-review-error redline.
