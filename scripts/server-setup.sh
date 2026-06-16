#!/bin/bash
set -e

# CS30 Server Setup Script
# Automates one-time server configuration: Java, Git, PostgreSQL, git repos, SSH keys

DB_USER="${DB_USER:-cs30}"
DB_PASS="${DB_PASS:-cs30pass}"
DB_NAME="${DB_NAME:-cs30db}"

echo "========================================"
echo "CS30 Server Setup"
echo "========================================"
echo ""

# 1. Install JDK 21
echo "[1/5] Installing JDK 21..."
if command -v java &> /dev/null && java -version 2>&1 | grep -q "21"; then
    echo "✓ Java 21 already installed"
else
    sudo apt update
    sudo apt install -y openjdk-21-jre-headless
    echo "✓ Java 21 installed"
fi
echo ""

# 2. Install Git
echo "[2/5] Installing Git..."
if command -v git &> /dev/null; then
    echo "✓ Git already installed"
else
    sudo apt install -y git
fi
git config --global user.email "server@cs30.edu"
git config --global user.name "CS30 Server"
echo "✓ Git configured"
echo ""

# 3. Set up PostgreSQL
echo "[3/5] Setting up PostgreSQL..."
if command -v psql &> /dev/null; then
    echo "✓ PostgreSQL already installed"
else
    sudo apt install -y postgresql postgresql-contrib
    sudo systemctl start postgresql
    sudo systemctl enable postgresql
fi

# Create DB user and database if they don't exist
if sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1; then
    echo "✓ Database '$DB_NAME' already exists"
else
    sudo -u postgres psql -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASS';"
    sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
    echo "✓ Database '$DB_NAME' created with user '$DB_USER'"
fi
echo ""

# 4. Create git repo directories
echo "[4/5] Creating git repositories..."
mkdir -p ~/cs30/repos/students
if [ ! -d ~/cs30/repos/students/.git ]; then
    cd ~/cs30/repos/students
    git init
    git commit --allow-empty -m "init"
    cd -
    echo "✓ Student repository initialized"
else
    echo "✓ Student repository already initialized"
fi

mkdir -p ~/cs30/repos/problems
# Flat global problem pool: ~/cs30/repos/problems/<problem-name>/index.html
# (populated by the CLI: addproblem / addproblems). Do NOT nest by section/lab.
if [ ! -d ~/cs30/repos/problems/.git ]; then
    cd ~/cs30/repos/problems && git init && cd -
fi
echo "✓ Problem repository directory created"
echo ""

# 5. Configure SSH authorized_keys
echo "[5/5] Configuring SSH for developer + backend access..."
mkdir -p ~/.ssh
chmod 700 ~/.ssh

# The backend reads problems and commits student code over SSH to this same host,
# so it needs passwordless SSH to itself (git.server.ssh-host=localhost).
if [ ! -f ~/.ssh/id_ed25519 ]; then
    ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519
    cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys
    echo "✓ Generated key and authorized backend SSH-to-self"
fi

echo ""
echo "Please paste your developer machine's SSH public key (from 'cat ~/.ssh/id_rsa.pub')"
echo "and press Enter when done:"
echo ""
read -r SSH_KEY

if [ -n "$SSH_KEY" ]; then
    echo "$SSH_KEY" >> ~/.ssh/authorized_keys
    chmod 600 ~/.ssh/authorized_keys
    echo "✓ SSH key added to authorized_keys"
else
    echo "⚠ No SSH key provided (you can add it manually later)"
fi
echo ""

# Done
echo "========================================"
echo "✓ Server setup complete!"
echo "========================================"
echo ""
echo "Remaining manual steps:"
echo ""
echo "1. Set up Google OAuth:"
echo "   - Go to Google Cloud Console > APIs & Credentials"
echo "   - Create OAuth 2.0 Client ID (Web application)"
echo "   - Add the redirect URI that matches google.redirect-uri (prod: http://<host>:8080/callback)"
echo "   - Copy Client ID and Secret into application.properties"
echo ""
echo "2. Edit application.properties:"
echo "   - git.server.ssh-host=localhost   (repos are co-located with the backend)"
echo "   - judge.url=http://<judge-host>:8000   (the judge runs on a SEPARATE host)"
echo "   - webapp.dir=$HOME/cs30/webapp   (the backend serves the web frontend from here)"
echo ""
echo "3. Copy artifacts to server:"
echo "   scp application.properties <user>@<server>:~/cs30/"
echo "   scp backend/build/libs/backend-1.0-SNAPSHOT.jar <user>@<server>:~/cs30/"
echo "   scp -r frontend/build/dist/wasmJs/productionExecutable/* <user>@<server>:~/cs30/webapp/"
echo ""
echo "4. Start the backend:"
echo "   cd ~/cs30"
echo "   java -jar backend-1.0-SNAPSHOT.jar --spring.config.location=file:./application.properties"
echo ""
