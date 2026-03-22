"""Sample project for testing callshow tracer."""
from utils import greet, compute


def main():
    message = greet("World")
    print(message)
    result = compute(5, 3)
    print(f"Result: {result}")


if __name__ == "__main__":
    main()
