import unittest

from fastapi import HTTPException

from server import sanitize_inputs, sanitize_text


class ServerSanitizerTest(unittest.TestCase):

    def test_sanitize_text_removes_invalid_surrogates_and_controls(self):
        raw = "教育部文件" + "\udbc0" + "\x00" + "\x07" + "\u3000\u3000( ４ )"
        sanitized, stats = sanitize_text(raw)

        self.assertEqual("教育部文件 ( ４ )", sanitized)
        self.assertGreaterEqual(stats["removed_chars"], 3)
        self.assertGreaterEqual(stats["normalized_whitespace"], 1)

    def test_sanitize_inputs_rejects_empty_after_sanitization(self):
        with self.assertRaises(HTTPException) as context:
            sanitize_inputs("\udbc0\udbc0   \u3000")

        self.assertEqual(422, context.exception.status_code)


if __name__ == "__main__":
    unittest.main()
