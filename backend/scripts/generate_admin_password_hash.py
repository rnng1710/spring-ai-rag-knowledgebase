"""Generate a BCrypt hash for init_admin.sql without echoing the password."""

from getpass import getpass

import bcrypt


def main():
    password = getpass("管理员密码: ")
    confirmation = getpass("再次输入密码: ")
    if not password or password != confirmation:
        raise SystemExit("密码为空或两次输入不一致")
    encoded_password = password.encode("utf-8")
    if len(encoded_password) > 72:
        raise SystemExit("BCrypt 密码不能超过 72 字节")
    password_hash = bcrypt.hashpw(encoded_password, bcrypt.gensalt(rounds=10))
    print("SET @admin_password_hash = '" + password_hash.decode("ascii") + "';")


if __name__ == "__main__":
    main()
