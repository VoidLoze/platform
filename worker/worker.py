import os
import time
import requests
import redis

redis_host = os.getenv("REDIS_HOST", "redis")
redis_port = int(os.getenv("REDIS_PORT", "6379"))
backend_url = os.getenv("BACKEND_URL", "http://backend:8080")


def main():
    client = redis.Redis(host=redis_host, port=redis_port, decode_responses=True)
    pubsub = client.pubsub()
    pubsub.subscribe("platform.events")
    print("Worker subscribed to platform.events")
    for message in pubsub.listen():
        if message["type"] != "message":
            continue
        data = message["data"]
        if data.startswith("AssignmentSubmitted|"):
            # Placeholder: in production parse payload and call review endpoint.
            print(f"Processing event: {data}")
            time.sleep(1)
            requests.get(f"{backend_url}/actuator/health", timeout=5)


if __name__ == "__main__":
    main()
