import json


def handler(request):
    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"ok": True, "service": "qareeb-cloud", "controlPath": False}),
    }
