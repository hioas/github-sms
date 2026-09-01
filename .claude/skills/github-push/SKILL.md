---
name: github-push
description: 将任意项目提交并推送到 GitHub。当用户说"提交到 GitHub / 推送 / push / 发布 / 上传到 github"时使用。自动识别前端(JS/TS)、Java、Python、Go 等生态，按生态生成/校验 .gitignore，执行安全预检、建仓、推送、token 清理全流程。
---

# github-push — 推送任意项目到 GitHub

将当前目录的项目提交并推送到用户指定的 GitHub 仓库 `<owner>/<repo>`。

## 环境事实（勿再询问）

| 项 | 值 |
|---|---|
| Token 文件 | `~/workspace/env/github.env`，键 `mkl_token`（经典 PAT，`ghp_` 开头，归属用户 `geeker-lait`） |
| Git 身份 | 优先取全局 `git config user.name/email`；缺省回退 `lait` / `lait.zhang@gmail.com` |
| 目标仓库 | 由用户指定 `<owner>/<repo>`；owner 可能是**组织或用户**，用 API 探测 |
| 默认分支 | `main` |

## 0. 识别项目类型

按根目录特征文件自动判定，决定用哪套 `.gitignore` 与产物清理：

| 生态 | 特征文件 |
|---|---|
| 前端 JS/TS | `package.json` / `yarn.lock` / `pnpm-lock.yaml` / `vite.config.*` / `next.config.*` |
| Java | `pom.xml` / `build.gradle` / `build.gradle.kts` / `settings.gradle` |
| Python | `requirements.txt` / `pyproject.toml` / `setup.py` / `Pipfile` |
| Go | `go.mod` / `go.sum` |

可多生态共存（如 Java 后端 + 前端子目录），按需合并多套规则。

## 1. .gitignore 生成/合并（按生态）

### 通用片段（所有项目都应有）

```
.DS_Store
*.log
.env
.env.*
.idea/
.vscode/
*.iml
```

### 密钥类文件（无论生态都应排除）

```
.env
.env.local
.env.*.local
*.private.env.json
application-local.yml
application-local.yaml
config.local.*
*.pem
id_rsa
id_rsa.pub
*.key
```

### 按生态追加

**前端 JS/TS：**
```
node_modules/
dist/
build/
coverage/
*.tsbuildinfo
npm-debug.log*
yarn-error.log*
pnpm-debug.log*
.pnpm-store/
.vite/
.next/
```

**Java（Maven/Gradle）：**
```
target/
*.class
*.jar
*.war
hs_err_pid*
.gradle/
```

**Python：**
```
__pycache__/
*.py[cod]
venv/
.venv/
env/
.pytest_cache/
.mypy_cache/
.ruff_cache/
*.egg-info/
dist/
```

**Go：**
```
bin/
*.test
*.out
coverage.out
```

> 注意：Go 的 `vendor/` 通常**保留提交**（离线依赖）；Java 与前端都可能有 `build/`，确认是产物目录才忽略。

### 合并规则

- 已有 `.gitignore`：按生态逐条补缺（grep 判断 pattern 是否已存在，避免重复行）。
- 没有 `.gitignore`：从上面拼出完整内容创建。

## 2. 安全预检（必做，先于 add/commit）

1. 扫描将提交内容，确认无真实密钥/密码（测试桩如 `SK01`/`secret123`/`P456`/`mock-*` 可放行）：
   ```bash
   grep -rniE "(password|secret|token|apikey|accesskey|BEGIN (RSA|OPENSSH|EC) PRIVATE)" \
     --include="*.java" --include="*.js" --include="*.ts" --include="*.jsx" --include="*.tsx" \
     --include="*.py" --include="*.go" --include="*.json" --include="*.yml" --include="*.yaml" \
     --include="*.properties" --include="*.md" src/ docs/ README.md 2>/dev/null | \
     grep -viE "mock-|test|placeholder|your-|your_|config\.|sensitive|禁止提交|TODO|example"
   ```
2. 初始化并暂存，确认产物/密钥文件被忽略（命中即 OK）：
   ```bash
   git init -b main 2>/dev/null
   git add -A
   git check-ignore .env node_modules target __pycache__ dist 2>/dev/null
   git status --short
   ```
3. 确认私密文件确实未进入暂存区（若误提交先 `git rm --cached` 处理）。

## 3. 提交

```bash
NAME=$(git config user.name || echo lait)
EMAIL=$(git config user.email || echo lait.zhang@gmail.com)
git -c user.name="$NAME" -c user.email="$EMAIL" commit -m "<信息>"
```

提交信息用项目语言概括改动要点。

## 4. 确认/创建远程仓库

```bash
TOKEN=$(grep '^mkl_token=' ~/workspace/env/github.env | head -1 | cut -d= -f2-)
OWNER=<owner>; REPO=<repo>

# 探测 owner 是组织还是个人用户
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: token $TOKEN" \
  https://api.github.com/orgs/$OWNER          # 200=组织, 404=个人用户

# 探测仓库是否已存在
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: token $TOKEN" \
  https://api.github.com/repos/$OWNER/$REPO    # 200=已存在, 404=需创建
```

- 仓库已存在 → 直接推送。
- 仓库不存在 → **可见性（public/private）需先与用户确认**，再按 owner 类型创建：
  - 组织：`POST https://api.github.com/orgs/$OWNER/repos`
  - 个人用户：`POST https://api.github.com/user/repos`
  - 请求体：`{"name":"$REPO","private":<true|false>,"description":"<一句话描述>"}`

## 5. 推送 + 清理 token

token 只用于本次内联推送，**禁止**写入 `.git/config`：

```bash
git push "https://x-access-token:${TOKEN}@github.com/$OWNER/$REPO.git" main

# remote 用不含 token 的干净地址
git remote add origin https://github.com/$OWNER/$REPO.git 2>/dev/null || \
  git remote set-url origin https://github.com/$OWNER/$REPO.git
git remote -v
# 验证 config 里没有泄漏 token
grep -r "x-access-token" .git/config && echo "WARN: token in config" || echo "OK: no token in git config"
```

## 6. 验证

```bash
curl -s -H "Authorization: token $TOKEN" https://api.github.com/repos/$OWNER/$REPO \
  | grep -E '"full_name"|"default_branch"|"private"'
```

## 常见问题

- **token 失效**：检查 `~/workspace/env/github.env` 里 `mkl_token` 是否被刷新；过期后提示用户更新。
- **403 / 权限不足**：确认 token 归属用户（`geeker-lait`）是目标仓库协作者或组织成员，且有 push 权限。
- **push 拒绝 non-fast-forward**：远程已有提交时，先 `git pull --rebase origin main`（用户要求合并时用普通 merge）。
- **意外提交了私密文件**：`git rm --cached <file>` 移除跟踪 + 加入 `.gitignore`，再推送修正提交（历史中仍可能留存，必要时联系 GitHub 支持清除缓存）。
- **非 main 分支**：若仓库默认分支不是 main，push 目标分支按 `git push ... HEAD` 或显式指定。
