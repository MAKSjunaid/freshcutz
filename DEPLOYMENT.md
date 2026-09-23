# Deploying FreshCutz — GitHub + Render + Neon (all free)

This gets your site a real `https://` link customers can open, and is what
makes the phone push notifications actually work (they need a real HTTPS
site — localhost can't send them to a phone).

Everything here fits inside free tiers:

- **Neon** (database): 0.5GB storage, plenty for text + product photos.
- **Render** (hosting, both backend and frontend): free web service +
  free static site.

Free tiers do have one trade-off worth knowing up front: if nobody visits
for a while, both Render's backend and Neon's database "fall asleep" to
save resources, and the first request after that takes 30–60 seconds to
wake them back up (every request after that is instant again). For a
one-person shop's order volume, this is normal and fine — just don't be
surprised the first time.

---

## Step 1 — Create your Neon database

1. Go to [neon.tech](https://neon.tech) and sign up (free, no card needed).
2. Create a new project — any name, e.g. `freshcutz`.
3. On the project dashboard, click **Connect** (or **Connection Details**)
   and copy the connection string. It looks like:
   ```
   postgresql://<user>:<password>@<host>/<database>?sslmode=require
   ```
4. Keep this tab open — you'll split this one string into three pieces
   (user, password, host+database) in Step 4 below.

You don't need to create any tables yourself — the backend does that
automatically the first time it connects.

## Step 2 — Push this project to GitHub

If you've never used git before, these are the exact commands to run, in
order, from inside the `freshcutz` project folder (a terminal / PowerShell
window opened in that folder):

```bash
git init
git add .
git commit -m "FreshCutz — initial commit"
```

Then create the actual repository on GitHub:

1. Go to [github.com/new](https://github.com/new).
2. Repository name: `freshcutz` (or anything you like). Keep it **Private**
   if you don't want the code public. Don't check "Add a README" — you
   already have one.
3. Click **Create repository**. GitHub then shows you a page with commands
   under "…or push an existing repository from the command line" — copy
   those exact three lines (they include your repository's unique URL and
   look like this, but with your own username/repo name):

```bash
git remote add origin https://github.com/<your-username>/freshcutz.git
git branch -M main
git push -u origin main
```

Run those in the same terminal. GitHub will ask you to sign in the first
time (a browser window usually pops up for this).

**Every time you make changes later** (after any future update), push them
with just:

```bash
git add .
git commit -m "describe what changed"
git push
```

Render is set up (next step) to redeploy automatically every time you push
to GitHub, so this is the only step you'll need going forward.

## Step 3 — One important local step before your first deploy

*(Skip this step entirely if this is a brand-new project with no products
uploaded yet — it only matters if you already uploaded product photos while
testing locally in an earlier round.)*

Product photos used to be saved as files on disk; they now live in the
database instead (see `README.md`). `ImageMigrationRunner` copies any old
disk-saved photos into the database automatically on startup — it checks a
few likely folders for the old files, so it doesn't matter exactly how you
normally run the app (terminal in `backend/`, an IDE run button, etc.). But
it can only see photos sitting on **your own computer's disk**, so run the
backend **once, locally, pointed at your real Neon database** before
deploying — that's what actually gets this round's uploaded photos into
Neon, ready for when the live site starts reading from it:

```bash
cd backend
export SPRING_DATASOURCE_URL="jdbc:postgresql://<host>/<database>?sslmode=require"
export SPRING_DATASOURCE_USERNAME="<user>"
export SPRING_DATASOURCE_PASSWORD="<password>"
mvn spring-boot:run
```

(If you normally run this project from an IDE instead, that's fine too —
just set the same three values as environment variables for that run
configuration, or paste them at the top of `application.properties`
temporarily, run once, then remove them again.)

Watch the startup logs for a line like `Migrated N of N product photo(s)
from disk into the database`. If it instead logs a warning that it
couldn't find a particular photo, that product will just show its old
placeholder until you re-upload the photo from the admin panel — nothing
else is affected. Once you see the migration finish (or confirm there was
nothing to migrate), stop the app (Ctrl+C) — you're ready to deploy.

## Step 4 — Deploy to Render

1. Go to [render.com](https://render.com) and sign up (free) — connect it
   to your GitHub account when prompted.
2. Click **New +** → **Blueprint**.
3. Pick the `freshcutz` repository you pushed in Step 2. Render reads the
   `render.yaml` file at the repo root and shows you two services it's
   about to create: `freshcutz-backend` and `freshcutz-frontend`.
4. Before clicking deploy, Render will prompt you to fill in the env vars
   marked `sync: false` in `render.yaml`:
   - `SPRING_DATASOURCE_URL` → `jdbc:postgresql://<host>/<database>?sslmode=require`
     (same as Step 1's connection string, but with `jdbc:` added to the
     front and `postgresql://<user>:<password>@` trimmed down to just
     `postgresql://<host>/<database>`)
   - `SPRING_DATASOURCE_USERNAME` → `<user>` from Step 1
   - `SPRING_DATASOURCE_PASSWORD` → `<password>` from Step 1
   - `ADMIN_USERNAME` → pick your own admin login username
   - `ADMIN_PASSWORD` → pick a real, strong password (don't leave the
     default `FreshCutz@2026` — this protects your admin dashboard)
5. Click **Apply** / **Deploy**. Render builds the Docker image (takes a
   few minutes the first time) and deploys both services. You'll get two
   URLs, something like:
   - `https://freshcutz-backend.onrender.com` (the API)
   - `https://freshcutz-frontend.onrender.com` (the site customers see)

## Step 5 — Point the frontend at the deployed backend

The frontend needs to know the backend's real URL (it defaults to
`localhost` for local testing):

1. Open `frontend/js/config.js`.
2. Change this line to your backend's Render URL from Step 4:
   ```js
   window.FRESHCUTZ_API_BASE = "https://freshcutz-backend.onrender.com";
   ```
3. Commit and push (see the "every time you make changes" commands in Step
   2) — Render redeploys the frontend automatically within a minute or two.

Your site is now live at `https://freshcutz-frontend.onrender.com`, and
your admin dashboard at `https://freshcutz-frontend.onrender.com/admin.html`.
That's the link to share with customers, and the one to open on your own
phone (Add to Home Screen) to receive push notifications.

## Keeping your Neon usage inside the free 500MB

- Product photo uploads are capped at 2MB each and always **replace** the
  previous photo for that product — they never pile up as extra records.
  A shop with ~50 products and a photo on each still uses well under 5MB
  total, leaving virtually all of your 500MB for orders and everything
  else.
- If you ever want to compress photos further before uploading, any phone
  photo editor's "reduce file size" / "compress" option works fine — this
  app doesn't need high resolution, just clear enough to recognize the cut.

## If something goes wrong

- **Render build fails**: check the build logs on the service's page — the
  most common cause is a typo in one of the `SPRING_DATASOURCE_*` values.
- **Site loads but products don't show up**: open the browser console on
  the site and check for a CORS or network error — usually means
  `frontend/js/config.js` still points at `localhost` instead of your
  Render backend URL (Step 5).
- **First request after a while is slow**: expected — see the free-tier
  note at the top of this file.
