"""Domain models for Series 76 actuators.

Shared contract between the ingestion pipeline (which produces `actuators.json`) and the
backend (which reads from Postgres). Every number comes from the electrical data PDF tables.
"""

from __future__ import annotations

import re
from decimal import Decimal
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

PART_NUMBER_RE = re.compile(r"^76\d[A-Z]\d{2}-[0-9A-Z]{8}/A$")


def normalize_part_number(value: str) -> str:
    """'761a00-11300000/a ' -> '761A0011300000A' (uppercase alphanumeric only)."""
    return re.sub(r"[^0-9A-Z]", "", value.upper())


class Enclosure(StrEnum):
    WEATHERPROOF = "weatherproof"
    EXPLOSIONPROOF = "explosionproof"


class CurrentType(StrEnum):
    AC = "ac"
    DC = "dc"
    AC_DC = "ac_dc"


class ServiceType(StrEnum):
    ON_OFF = "on_off"
    MODULATING = "modulating"
    # The 3-phase table gives a single rating without separating On/Off and Modulating.
    GENERAL = "general"


class _Base(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class ServiceRating(_Base):
    service_type: ServiceType
    torque_inlb: int = Field(gt=0)
    torque_nm: int = Field(gt=0)
    duty_cycle_pct: int = Field(gt=0, le=100)
    cycles_per_hour: int | None = Field(default=None, gt=0)
    starts_per_hour: int | None = Field(default=None, gt=0)


class ElectricalPoint(_Base):
    # None = not specific to a frequency (24V AC/DC and DC tables).
    frequency_hz: Literal[50, 60] | None
    operating_speed_s: int = Field(gt=0)
    fla_a: Decimal = Field(gt=0, decimal_places=1)
    lra_a: Decimal = Field(gt=0, decimal_places=1)

    @model_validator(mode="after")
    def _lra_not_below_fla(self) -> ElectricalPoint:
        if self.lra_a < self.fla_a:
            raise ValueError(f"LRA ({self.lra_a}) < FLA ({self.fla_a})")
        return self


class SourceRef(_Base):
    table_id: str
    page: int = Field(ge=1)
    row_index: int = Field(ge=0)


class SourceTable(_Base):
    table_id: str
    page: int = Field(ge=1)
    title: str
    enclosure: Enclosure
    certifications: list[str]
    power_desc: str
    notes: list[str] = []


class Actuator(_Base):
    base_part_number: str
    enclosure: Enclosure
    certifications: list[str]
    csa_certified: bool
    voltage_v: int
    phase: Literal[1, 3] | None  # None for 24V AC/DC / DC
    current_type: CurrentType
    motor_power_w: int | None = Field(default=None, gt=0)
    data_available: bool
    ratings: list[ServiceRating] = []
    electrical: list[ElectricalPoint] = []
    source: SourceRef

    @field_validator("base_part_number")
    @classmethod
    def _valid_part_number(cls, v: str) -> str:
        if not PART_NUMBER_RE.match(v):
            raise ValueError(f"invalid part number format: {v!r}")
        return v

    @model_validator(mode="after")
    def _data_consistency(self) -> Actuator:
        has_data = bool(self.ratings or self.electrical or self.motor_power_w)
        if self.data_available and not (self.ratings and self.electrical):
            raise ValueError(f"{self.base_part_number}: data_available but no ratings/electrical")
        if not self.data_available and has_data:
            raise ValueError(f"{self.base_part_number}: marked N/A but has data")
        services = [r.service_type for r in self.ratings]
        if len(services) != len(set(services)):
            raise ValueError(f"{self.base_part_number}: duplicated service type")
        freqs = [e.frequency_hz for e in self.electrical]
        if len(freqs) != len(set(freqs)):
            raise ValueError(f"{self.base_part_number}: duplicated frequency")
        return self

    # Fields derived from the ID (they don't come from a table column).
    @property
    def part_number_normalized(self) -> str:
        return normalize_part_number(self.base_part_number)

    @property
    def model_code(self) -> str:
        return self.base_part_number[:4]  # e.g. '761A'

    @property
    def size(self) -> int:
        return int(self.base_part_number[2])


class Catalog(_Base):
    """Contents of data/processed/actuators.json."""

    source_pdf: str
    tables: list[SourceTable]
    actuators: list[Actuator]
