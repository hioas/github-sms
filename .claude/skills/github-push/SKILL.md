---
name: github-push
description: 将 hioas-sms 项目提交并推送到 GitHub 仓库 hioas/github-sms。当用户说"提交到 GitHub / 推送 / push / 发布到 github"时使用。包含安全预检、建仓、推送、清理 token 的完整流程。
---

# github-push — 推送 hioas-sms 到 GitHub

将当前项目提交并推送到 **https://github.com/hioas/github-sms**（组织 `hioas`，默认分支 `main`）。

## 关键事实（勿再询问）

| 项 | 值 |
|---|---|
| 远程仓库 | `https://github.com/hioas/github-sms.git` |
| 组织 | `hioas`（GitHub 组织，token 归属用户 `geeker-lait`） |
| 默认分支 | `main` |
| Token 文件 | `~/workspace/env/github.env`，键名 `mkl_token`（经典 PAT，`ghp_` 开头） |
| Git 身份 | `lait` / `lait.zhang@gmail.com` |
| 当前工作目录 | `/Users/lait/workspace/java/hioas-sms`（Maven 项目） |

## 流程

### 1. 安全预检（必做，先于任何 add/commit）

真实凭证只允许存在于 `docs/examples/http-client.private.env.json`（已 gitignore）。推送前确认：

1. 检查 `.gitignore` 覆盖：
   - `target/`、`.idea/`、`*.iml`、`.DS_Store`
   - `docs/examples/http-client.private.env.json`（真实账号密钥）
2. 扫描将要提交的内容，确认没有真实密钥/密码（源码中 `SK01`/`secret123`/`P456`/`mock-*` 均为测试桩，可放行）：
   ```bash
   grep -rniE "(password|secret|token|apikey|accesskey|mkl_token)" \
     --include="*.java" --include="*.json" --include="*.yml" --include="*.yaml" \
     --include="*.properties" --include="*.md" src/ docs/ README.md 2>/dev/null | \
     grep -viE "mock-|test|Test|placeholder|your-|your_|config\.|sensitive|禁止提交"
   ```
3. 初始化并暂存后确认私密文件确实被忽略：
   ```bash
   git init -b main 2>/dev/null   # 已是仓库则跳过
   git add -A
   git check-ignore docs/examples/http-client.private.env.json target .idea && echo "ignored OK"
   git status --short
   ```

### 2. 提交

```bash
git -c user.name=lait -c user.email=lait.zhang@gmail.com commit -m "<信息>"
```

提交信息用项目语言（中文）描述改动要点。

### 3. 确认/创建远程仓库

先查状态（token 仅本次 shell 内使用，不要 echo 值）：

```bash
TOKEN=$(grep '^mkl_token=' ~/workspace/env/github.env | head -1 | cut -d= -f2-)
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: token $TOKEN" https://api.github.com/repos/hioas/github-sms
```

- `200`：已存在，直接推送。
- `404`：需要创建。**可见性需先与用户确认**（public/private）后再创建：
  ```bash
  curl -s -X POST -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    https://api.github.com/orgs/hioas/repos \
    -d '{"name":"github-sms","private":<true|false>,"description":"hioas-sms 多渠道短信网关（19 个厂商渠道，JSON 描述驱动）"}'
  ```

> 注意：`hioas` 是组织（orgs/hioas 返回 200），仓库创建走 `orgs/{org}/repos` 接口，不是 `user/repos`。

### 4. 推送 + 清理 token

token 只用于本次内联推送，**禁止**写入 `.git/config`：

```bash
TOKEN=$(grep '^mkl_token=' ~/workspace/env/github.env | head -1 | cut -d= -f2-)
git push "https://x-access-token:${TOKEN}@github.com/hioas/github-sms.git" main

# remote 用不含 token 的干净地址
git remote add origin https://github.com/hioas/github-sms.git 2>/dev/null || \
  git remote set-url origin https://github.com/hioas/github-sms.git
git remote -v
# 验证 config 里没有泄漏 token
grep -r "x-access-token" .git/config && echo "WARN: token in config" || echo "OK: no token in git config"
```

### 5. 验证

```bash
curl -s -H "Authorization: token $TOKEN" https://api.github.com/repos/hioas/github-sms \
  | grep -E '"full_name"|"default_branch"|"private"'
```

## 常见问题

- **token 失效**：检查 `~/workspace/env/github.env` 里 `mkl_token` 是否被刷新；过期后提示用户更新。
- **403 / 权限不足**：确认 `geeker-lait` 是 `hioas` 组织成员且有 push 权限。
- **push 拒绝 non-fast-forward**：远程已有提交时，先 `git pull --rebase origin main`（如用户要求合并，用普通 merge）。
- **意外提交了私密文件**：用 `git rm --cached <file>` 移除跟踪 + 加入 `.gitignore`，然后推送修正提交（历史里仍可能留存，必要时联系 GitHub 支持清除缓存）。
