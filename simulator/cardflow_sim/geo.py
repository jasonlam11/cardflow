"""Synthetic geography: a handful of real cities and great-circle distance."""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class City:
    name: str
    country: str
    lat: float
    lon: float
    # Relative share of cardholders who live here
    weight: float = 1.0


CITIES: tuple[City, ...] = (
    City("New York", "US", 40.7128, -74.0060, 5),
    City("Los Angeles", "US", 34.0522, -118.2437, 4),
    City("Chicago", "US", 41.8781, -87.6298, 3),
    City("Houston", "US", 29.7604, -95.3698, 3),
    City("Phoenix", "US", 33.4484, -112.0740, 2),
    City("Boston", "US", 42.3601, -71.0589, 2),
    City("Seattle", "US", 47.6062, -122.3321, 2),
    City("Miami", "US", 25.7617, -80.1918, 2),
    City("Denver", "US", 39.7392, -104.9903, 2),
    City("Atlanta", "US", 33.7490, -84.3880, 2),
    City("Toronto", "CA", 43.6532, -79.3832, 1),
    City("London", "GB", 51.5074, -0.1278, 1),
    City("Mexico City", "MX", 19.4326, -99.1332, 1),
    City("Tokyo", "JP", 35.6762, 139.6503, 0.5),
)


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance along the Earth's surface between two points, in kilometres."""
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))
