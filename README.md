# FreshCutz — Online Ordering Website

A complete ordering website for FreshCutz: customers browse your menu and place
orders from their phone, and you manage products + orders from a private admin
page. No more typing menus into WhatsApp one by one.

```
freshcutz/
├── backend/     Spring Boot API (Java) — products, orders, admin login, stats
└── frontend/    Plain HTML/CSS/JS — the customer site + admin dashboard
```

Every source file is kept small and single-purpose (under ~250 lines) so it's
easy to find and change the one thing you need.

## What's included

- **Customer site** (`frontend/index.html`) — product grid with photos, 500g/1kg
  pricing, cart, and a one-page checkout form (name, phone, flat no., address).
  Mobile-first and animated; works great as the link you share on WhatsApp.
- **Admin dashboard** (`frontend/admin.html`) — password-protected. Add/edit
  products, upload photos, enable/disable items, see new orders as they come
  in, mark them delivered, and view today / 7-day / 30-day / all-time sales.
- **Backend API** (`backend/`) — Spring Boot. Ships with your current price
  list already loaded as starting products.

Payments are Cash on Delivery, same as your WhatsApp flow today — the backend
doesn't touch payments at all.

## 1. Set up your database (Neon)

The backend defaults to a local file database so it runs with zero setup, but
since you're using [Neon](https://neon.tech) (free tier Postgres):

1. Create a free Neon account and a new project.
2. Open the project's **Connection Details** and copy the connection string.
   It looks like:
   `postgresql://<user>:<password>@<host>/<database>?sslmode=require`
3. Set three environment variables before starting the backend (see below):

   ```
   SPRING_DATASOURCE_URL=jdbc:postgresql://<host>/<database>?sslmode=require
   SPRING_DATASOURCE_USERNAME=<user>
   SPRING_DATASOURCE_PASSWORD=<password>
   ```

   Note the `jdbc:` prefix added to the front of Neon's URL — that's required.

The app creates its tables automatically on first run and loads your current
price list as starting products (only if the products table is empty).

Product photos (including ones you upload from the admin panel) are stored
**inside this same database**, not as files on disk — see "Product photos"
in `application.properties` for why. That's also what makes free hosting
possible: Render's free plan wipes its disk on every restart, but your
Neon database survives untouched.

Want to deploy this for real (GitHub + Render + Neon, all free)? See
**[DEPLOYMENT.md](DEPLOYMENT.md)** — it walks through the whole thing
step by step, including this Neon setup.

## 2. Run the backend

Requirements: **Java 21** and **Maven** (or just use the included `mvnw` if
you add one — plain `mvn` works fine too).

```bash
cd backend

# change the admin password first! see step 4 below

# macOS/Linux
export SPRING_DATASOURCE_URL="jdbc:postgresql://<host>/<database>?sslmode=require"
export SPRING_DATASOURCE_USERNAME="<user>"
export SPRING_DATASOURCE_PASSWORD="<password>"
mvn spring-boot:run

# Windows PowerShell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://<host>/<database>?sslmode=require"
$env:SPRING_DATASOURCE_USERNAME="<user>"
$env:SPRING_DATASOURCE_PASSWORD="<password>"
mvn spring-boot:run
```

The API starts on **http://localhost:8080**. Leave this running.

To build a runnable jar instead (for deploying somewhere): `mvn clean package`
then `java -jar target/freshcutz-backend.jar`.

> **A note on this build**: I wrote and reviewed every file carefully, but the
> sandbox this was built in couldn't reach Maven Central to actually compile
> it (its network is locked down to a few package registries). Run
> `mvn clean package` once on your own machine to confirm it builds — if
> anything doesn't compile, paste me the error and I'll fix it immediately.

## 3. Open the frontend

The frontend is plain HTML/CSS/JS — no build step.

- **Quick local test**: open `frontend/js/config.js` and confirm
  `FRESHCUTZ_API_BASE` points at `http://localhost:8080`, then just double-click
  `frontend/index.html` to open it in a browser (or use a simple local server,
  e.g. `npx serve frontend`).
- **Deploying for real** (recommended, since customers need a real link, and
  it's what makes push notifications actually reach a phone): see
  **[DEPLOYMENT.md](DEPLOYMENT.md)** for the full, free GitHub + Render +
  Neon walkthrough — a `render.yaml` in this repo deploys both the backend
  and this frontend in one go. (Railway, Fly.io, Netlify, Vercel, GitHub
  Pages and Cloudflare Pages are other options if you'd rather not use
  Render, but aren't what's set up here.)

## 4. Change the admin login

**Do this before sharing your site with anyone.** The admin username/password
are set via environment variables (defaults are placeholders, not secure):

```
ADMIN_USERNAME=your_username
ADMIN_PASSWORD=a_strong_password
```

Set these alongside the database env vars wherever the backend runs. Without
them, the fallback is `admin` / `FreshCutz@2026` from `application.properties`
— fine for local testing, not for a public site.

Go to `admin.html`, sign in, and you're in. Sessions last 12 hours (see
`freshcutz.admin.token-ttl-hours` in `application.properties` to change that).

## 5. Add your product photos

Your price list is already loaded as products, three with your real photos
(Curry Cut, Breast, Drumsticks) and the rest with a branded placeholder.
From the admin **Products** tab, tap "Change photo" on any item to upload a
real picture (JPG/PNG/WEBP, up to 2MB) — it updates on the live site
instantly, and **replaces** the old photo rather than storing a second copy,
so re-uploading a photo never eats into your storage.

## Everyday use

- **New order comes in** → it shows up under the **Orders** tab (badge on the
  nav shows how many are new) with the customer's name, phone, flat/address,
  and what they ordered. Tap **Mark Delivered** once it's out, or **Cancel**
  if needed.
- **Prices change** → **Products** tab → **Edit** on the item → save.
- **Out of stock** → flip the toggle on that product to hide it from
  customers without deleting it (their order history is unaffected either
  way — past orders always keep the price/name from when they were placed).
- **How much did I sell?** → **Sales** tab: today, last 7 days, last 30 days,
  all time.

## Notes on the design

- The whole site is mobile-first — it's built and tested primarily for
  phone screens (2-column product grid, bottom sheet cart, big tap targets),
  and scales up cleanly to tablet/desktop.
- Admin login is a single hardcoded username/password (no user database) by
  design, since this is a one-person shop — see step 4 to set it.
- CORS is wide open by default (`freshcutz.cors.allowed-origins=*`) since only
  the admin API needs protecting, and that uses a token, not cookies. You can
  restrict it to your exact frontend domain via `CORS_ALLOWED_ORIGINS` once
  deployed, if you'd like.
