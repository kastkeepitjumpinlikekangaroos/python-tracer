"""Utility functions for sample project."""
from lib.helper import add_numbers


def greet(name):
    return f"Hello, {name}!"


def compute(a, b):
    return add_numbers(a, b)
