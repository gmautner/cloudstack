#!/usr/bin/env python3
"""
CloudStack SAML Authentication Helper

Opens a browser for SAML authentication at a CloudStack instance,
waits for the user to complete login, then extracts the session
credentials and returns API key/secret pair.

If API keys already exist for the user, returns them without
regenerating (to avoid invalidating keys that may be in use).
Use --force-new-keys to regenerate even if keys exist.

Requirements:
    pip install selenium requests

Usage:
    python cs-saml-auth.py
    python cs-saml-auth.py --force-new-keys
    python cs-saml-auth.py --url https://my-cloudstack.example.com
"""

import argparse
import json
import sys
import time
import urllib.parse
from hashlib import sha1
import hmac
import base64

try:
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service
    from selenium.webdriver.support.ui import WebDriverWait
except ImportError:
    print("Error: selenium is required. Install it with:")
    print("  pip install selenium")
    sys.exit(1)

try:
    import requests
except ImportError:
    print("Error: requests is required. Install it with:")
    print("  pip install requests")
    sys.exit(1)


DEFAULT_URL = "https://painel-cloud.locaweb.com.br"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Authenticate to CloudStack via SAML and extract API credentials"
    )
    parser.add_argument(
        "--url",
        default=DEFAULT_URL,
        help=f"CloudStack base URL (default: {DEFAULT_URL})",
    )
    parser.add_argument(
        "--force-new-keys",
        action="store_true",
        help="Generate new API keys even if they already exist (invalidates old ones)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=300,
        help="Max seconds to wait for authentication (default: 300)",
    )
    parser.add_argument(
        "--chrome-binary",
        default=None,
        help="Path to Chrome/Chromium binary (auto-detected if not set)",
    )
    return parser.parse_args()


def build_api_url(base_url):
    """Build the CloudStack API endpoint URL."""
    return base_url.rstrip("/") + "/client/api"


def start_browser(base_url, chrome_binary=None):
    """Start a Chrome browser and navigate to the CloudStack login page."""
    options = Options()
    if chrome_binary:
        options.binary_location = chrome_binary
    # Keep browser open after script finishes if needed
    options.add_experimental_option("detach", False)

    driver = webdriver.Chrome(options=options)
    driver.get(base_url + "/client/")
    return driver


def wait_for_saml_auth(driver, base_url, timeout):
    """
    Wait for the user to complete SAML authentication.
    Detection: after SAML completes, CloudStack redirects back to its UI
    and sets the sessionkey cookie.
    """
    cloudstack_host = urllib.parse.urlparse(base_url).hostname

    print(f"\nWaiting for you to authenticate (timeout: {timeout}s)...")
    print("Complete the login in the browser window that opened.\n")

    start_time = time.time()
    while time.time() - start_time < timeout:
        try:
            current_url = driver.current_url
            current_host = urllib.parse.urlparse(current_url).hostname

            # Check if we're back on the CloudStack domain
            if current_host == cloudstack_host:
                cookies = {c["name"]: c["value"] for c in driver.get_cookies()}
                if "sessionkey" in cookies:
                    return cookies

                # Also check localStorage (the UI stores sessionkey there)
                try:
                    session_key = driver.execute_script(
                        "return window.localStorage.getItem('Access-Token') "
                        "|| window.localStorage.getItem('pro__Access-Token');"
                    )
                    if session_key:
                        cookies["sessionkey"] = session_key
                        return cookies
                except Exception:
                    pass

        except Exception:
            # Browser might be navigating, ignore transient errors
            pass

        time.sleep(1)

    return None


def extract_credentials(cookies):
    """Extract CloudStack credentials from browser cookies."""
    creds = {}
    fields = [
        "sessionkey",
        "userid",
        "username",
        "account",
        "domainid",
        "role",
        "userfullname",
        "timezone",
    ]
    for field in fields:
        val = cookies.get(field)
        if val:
            creds[field] = urllib.parse.unquote(val)
    # JSESSIONID is required to identify the server-side session;
    # sessionkey alone is just a CSRF token validated against it.
    jsessionid = cookies.get("JSESSIONID")
    if jsessionid:
        creds["JSESSIONID"] = jsessionid
    return creds


def cs_api_call(base_url, creds, command, params=None):
    """Make a CloudStack API call using session authentication."""
    api_url = build_api_url(base_url)
    sessionkey = creds["sessionkey"]
    req_params = {"command": command, "response": "json", "sessionkey": sessionkey}
    if params:
        req_params.update(params)

    # JSESSIONID identifies the server-side session; sessionkey is CSRF protection
    cookies = {}
    if creds.get("JSESSIONID"):
        cookies["JSESSIONID"] = creds["JSESSIONID"]
    resp = requests.get(api_url, params=req_params, cookies=cookies)
    resp.raise_for_status()
    return resp.json()


def get_existing_api_keys(base_url, creds):
    """Check if the user already has API keys via getUserKeys."""
    userid = creds.get("userid")
    try:
        result = cs_api_call(base_url, creds, "getUserKeys", {"id": userid})
        user_keys = result.get("getuserkeysresponse", {}).get("userkeys", {})
        apikey = user_keys.get("apikey")
        secretkey = user_keys.get("secretkey")
        if apikey and secretkey:
            return {"apikey": apikey, "secretkey": secretkey}
    except requests.exceptions.HTTPError:
        pass
    return None


def register_new_api_keys(base_url, creds):
    """Call registerUserKeys to generate a new API key/secret pair."""
    userid = creds.get("userid")
    try:
        result = cs_api_call(
            base_url, creds, "registerUserKeys", {"id": userid}
        )
        user_keys = result.get("registeruserkeysresponse", {}).get("userkeys", {})
        return {
            "apikey": user_keys.get("apikey"),
            "secretkey": user_keys.get("secretkey"),
        }
    except requests.exceptions.HTTPError as e:
        print(f"Error generating API keys: {e}")
        print(f"Response: {e.response.text if e.response else 'N/A'}")
        return None


def get_or_create_api_keys(base_url, creds, force_new=False):
    """Return API keys, reusing existing ones unless force_new is set."""
    userid = creds.get("userid")
    username = creds.get("username", userid)

    if not creds.get("userid") or not creds.get("sessionkey"):
        print("Error: Missing userid or sessionkey, cannot retrieve API keys.")
        return None

    if not force_new:
        print(f"\nChecking existing API keys for user {username}...")
        existing = get_existing_api_keys(base_url, creds)
        if existing:
            print("  Found existing API keys (reusing them).")
            return existing
        print("  No existing keys found.")

    print(f"Generating new API keys for user {username}...")
    return register_new_api_keys(base_url, creds)


def print_credentials(creds, api_keys=None):
    """Print credentials in a usable format."""
    print("\n" + "=" * 60)
    print("CLOUDSTACK SESSION CREDENTIALS")
    print("=" * 60)
    for key, val in creds.items():
        if key.startswith("_"):
            continue
        print(f"  {key}: {val}")

    if api_keys:
        print("\n" + "-" * 60)
        print("API KEY / SECRET (permanent until regenerated)")
        print("-" * 60)
        print(f"  apikey:    {api_keys['apikey']}")
        print(f"  secretkey: {api_keys['secretkey']}")

    print("\n" + "-" * 60)
    print("EXAMPLE USAGE (session-based, temporary):")
    print("-" * 60)
    sk = creds.get("sessionkey", "<sessionkey>")
    jsid = creds.get("JSESSIONID", "<JSESSIONID>")
    base = creds.get("_base_url", DEFAULT_URL)
    print(f'  curl -b "JSESSIONID={jsid}" \\')
    print(f'    "{base}/client/api'
          f'?command=listVirtualMachines&response=json&sessionkey={urllib.parse.quote(sk, safe="")}"')

    if api_keys:
        print("\n" + "-" * 60)
        print("EXAMPLE USAGE (API key/secret, permanent):")
        print("-" * 60)
        print(f"  See generate_signed_url() in this script, or use CloudMonkey:")
        print(f"  cmk set url {DEFAULT_URL}/client/api")
        print(f"  cmk set apikey {api_keys['apikey']}")
        print(f"  cmk set secretkey {api_keys['secretkey']}")

    print("=" * 60)


def print_env_export(creds, api_keys=None):
    """Print shell export commands."""
    base = creds.get("_base_url", DEFAULT_URL)
    print("\n# Shell exports (copy-paste into your terminal):")
    print(f"export CS_URL=\"{base}/client/api\"")
    print(f"export CS_SESSIONKEY=\"{creds.get('sessionkey', '')}\"")
    print(f"export CS_JSESSIONID=\"{creds.get('JSESSIONID', '')}\"")
    if api_keys:
        print(f"export CS_API_KEY=\"{api_keys.get('apikey', '')}\"")
        print(f"export CS_SECRET_KEY=\"{api_keys.get('secretkey', '')}\"")


def main():
    args = parse_args()
    base_url = args.url.rstrip("/")

    print(f"CloudStack SAML Authentication Helper")
    print(f"Target: {base_url}")
    print(f"\nOpening browser for SAML login...")

    driver = None
    try:
        driver = start_browser(base_url, args.chrome_binary)
        cookies = wait_for_saml_auth(driver, base_url, args.timeout)

        if not cookies:
            print("\nError: Authentication timed out or failed.")
            print("No sessionkey was found in browser cookies/localStorage.")
            sys.exit(1)

        creds = extract_credentials(cookies)
        if not creds.get("sessionkey"):
            print("\nError: Could not extract sessionkey from browser state.")
            sys.exit(1)

        creds["_base_url"] = base_url
        print("\nAuthentication successful!")

        api_keys = get_or_create_api_keys(base_url, creds, force_new=args.force_new_keys)

        print_credentials(creds, api_keys)
        print_env_export(creds, api_keys)

    except KeyboardInterrupt:
        print("\nAborted.")
        sys.exit(1)
    finally:
        if driver:
            try:
                driver.quit()
            except Exception:
                pass


if __name__ == "__main__":
    main()
