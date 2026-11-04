"""actuator catalog and search schema

Revision ID: 0001
Revises: 
Create Date: 2026-11-03 13:22:04.741669
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
import pgvector.sqlalchemy
from sqlalchemy.dialects import postgresql

from app.config import get_settings

revision: str = '0001'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


EMBEDDING_DIM = get_settings().embedding_dim

# One row per part number with everything pivoted. This is what the tools query.
# Suffixes: _60hz/_50hz = per-frequency values; _nominal = not frequency-specific (24V tables).
ACTUATOR_FLAT_VIEW = """
CREATE VIEW actuator_flat AS
SELECT
    a.base_part_number, a.part_number_normalized, a.model_code, a.size,
    a.enclosure, a.certifications, a.csa_certified,
    a.voltage_v, a.phase, a.current_type, a.motor_power_w, a.data_available,
    a.source_table_id, st.page AS source_page, st.title AS source_title,
    oo.torque_inlb AS onoff_torque_inlb, oo.torque_nm AS onoff_torque_nm,
    oo.duty_cycle_pct AS onoff_duty_cycle_pct, oo.cycles_per_hour AS onoff_cycles_per_hour,
    mo.torque_inlb AS mod_torque_inlb, mo.torque_nm AS mod_torque_nm,
    mo.duty_cycle_pct AS mod_duty_cycle_pct, mo.starts_per_hour AS mod_starts_per_hour,
    ge.torque_inlb AS general_torque_inlb, ge.torque_nm AS general_torque_nm,
    ge.duty_cycle_pct AS general_duty_cycle_pct, ge.cycles_per_hour AS general_cycles_per_hour,
    ge.starts_per_hour AS general_starts_per_hour,
    e60.operating_speed_s AS speed_60hz_s, e60.fla_a AS fla_60hz_a, e60.lra_a AS lra_60hz_a,
    e50.operating_speed_s AS speed_50hz_s, e50.fla_a AS fla_50hz_a, e50.lra_a AS lra_50hz_a,
    en.operating_speed_s AS speed_nominal_s, en.fla_a AS fla_nominal_a, en.lra_a AS lra_nominal_a
FROM actuator a
JOIN source_table st ON st.id = a.source_table_id
LEFT JOIN actuator_rating oo ON oo.base_part_number = a.base_part_number AND oo.service_type = 'on_off'
LEFT JOIN actuator_rating mo ON mo.base_part_number = a.base_part_number AND mo.service_type = 'modulating'
LEFT JOIN actuator_rating ge ON ge.base_part_number = a.base_part_number AND ge.service_type = 'general'
LEFT JOIN actuator_electrical e60 ON e60.base_part_number = a.base_part_number AND e60.frequency_hz = 60
LEFT JOIN actuator_electrical e50 ON e50.base_part_number = a.base_part_number AND e50.frequency_hz = 50
LEFT JOIN actuator_electrical en ON en.base_part_number = a.base_part_number AND en.frequency_hz IS NULL
"""


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")  # approach B (fuzzy part number)
    op.create_table('source_table',
    sa.Column('id', sa.Text(), nullable=False),
    sa.Column('page', sa.SmallInteger(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('enclosure', sa.Text(), nullable=False),
    sa.Column('certifications', sa.ARRAY(sa.Text()), nullable=False),
    sa.Column('power_desc', sa.Text(), nullable=False),
    sa.Column('notes', postgresql.JSONB(astext_type=sa.Text()), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_table('actuator',
    sa.Column('base_part_number', sa.Text(), nullable=False),
    sa.Column('part_number_normalized', sa.Text(), nullable=False),
    sa.Column('model_code', sa.Text(), nullable=False),
    sa.Column('size', sa.SmallInteger(), nullable=False),
    sa.Column('enclosure', sa.Text(), nullable=False),
    sa.Column('certifications', sa.ARRAY(sa.Text()), nullable=False),
    sa.Column('csa_certified', sa.Boolean(), nullable=False),
    sa.Column('voltage_v', sa.SmallInteger(), nullable=False),
    sa.Column('phase', sa.SmallInteger(), nullable=True),
    sa.Column('current_type', sa.Text(), nullable=False),
    sa.Column('motor_power_w', sa.SmallInteger(), nullable=True),
    sa.Column('data_available', sa.Boolean(), nullable=False),
    sa.Column('source_table_id', sa.Text(), nullable=False),
    sa.Column('source_row', sa.SmallInteger(), nullable=False),
    sa.CheckConstraint("current_type IN ('ac', 'dc', 'ac_dc')", name='ck_actuator_current_type'),
    sa.CheckConstraint("enclosure IN ('weatherproof', 'explosionproof')", name='ck_actuator_enclosure'),
    sa.CheckConstraint('phase IS NULL OR phase IN (1, 3)', name='ck_actuator_phase'),
    sa.ForeignKeyConstraint(['source_table_id'], ['source_table.id'], ),
    sa.PrimaryKeyConstraint('base_part_number'),
    sa.UniqueConstraint('part_number_normalized')
    )
    op.create_index(op.f('ix_actuator_model_code'), 'actuator', ['model_code'], unique=False)
    op.create_table('actuator_electrical',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('base_part_number', sa.Text(), nullable=False),
    sa.Column('frequency_hz', sa.SmallInteger(), nullable=True),
    sa.Column('operating_speed_s', sa.SmallInteger(), nullable=False),
    sa.Column('fla_a', sa.Numeric(precision=4, scale=1), nullable=False),
    sa.Column('lra_a', sa.Numeric(precision=4, scale=1), nullable=False),
    sa.CheckConstraint('frequency_hz IS NULL OR frequency_hz IN (50, 60)', name='ck_elec_freq'),
    sa.CheckConstraint('lra_a >= fla_a', name='ck_elec_lra_ge_fla'),
    sa.ForeignKeyConstraint(['base_part_number'], ['actuator.base_part_number'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('base_part_number', 'frequency_hz', postgresql_nulls_not_distinct=True)
    )
    op.create_index(op.f('ix_actuator_electrical_base_part_number'), 'actuator_electrical', ['base_part_number'], unique=False)
    op.create_table('actuator_rating',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('base_part_number', sa.Text(), nullable=False),
    sa.Column('service_type', sa.Text(), nullable=False),
    sa.Column('torque_inlb', sa.Integer(), nullable=False),
    sa.Column('torque_nm', sa.Integer(), nullable=False),
    sa.Column('duty_cycle_pct', sa.SmallInteger(), nullable=False),
    sa.Column('cycles_per_hour', sa.SmallInteger(), nullable=True),
    sa.Column('starts_per_hour', sa.SmallInteger(), nullable=True),
    sa.CheckConstraint("service_type IN ('on_off', 'modulating', 'general')", name='ck_rating_service'),
    sa.CheckConstraint('duty_cycle_pct > 0 AND duty_cycle_pct <= 100', name='ck_rating_duty'),
    sa.ForeignKeyConstraint(['base_part_number'], ['actuator.base_part_number'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('base_part_number', 'service_type')
    )
    op.create_index(op.f('ix_actuator_rating_base_part_number'), 'actuator_rating', ['base_part_number'], unique=False)
    op.create_table('search_document',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('base_part_number', sa.Text(), nullable=True),
    sa.Column('doc_type', sa.Text(), nullable=False),
    sa.Column('title', sa.Text(), nullable=False),
    sa.Column('content', sa.Text(), nullable=False),
    sa.Column('lexical_text', sa.Text(), nullable=False),
    sa.Column('tsv', postgresql.TSVECTOR(), sa.Computed("to_tsvector('simple', lexical_text)", persisted=True), nullable=False),
    sa.Column('embedding', pgvector.sqlalchemy.vector.VECTOR(dim=EMBEDDING_DIM), nullable=True),
    sa.Column('embedding_model', sa.Text(), nullable=True),
    sa.CheckConstraint("doc_type IN ('actuator', 'context')", name='ck_doc_type'),
    sa.ForeignKeyConstraint(['base_part_number'], ['actuator.base_part_number'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_search_document_base_part_number'), 'search_document', ['base_part_number'], unique=False)
    op.create_index('ix_search_document_tsv', 'search_document', ['tsv'], unique=False, postgresql_using='gin')
    op.execute(
        "CREATE INDEX ix_actuator_pn_trgm ON actuator USING gin (part_number_normalized gin_trgm_ops)"
    )
    op.execute(ACTUATOR_FLAT_VIEW)


def downgrade() -> None:
    op.execute("DROP VIEW IF EXISTS actuator_flat")
    op.execute("DROP INDEX IF EXISTS ix_actuator_pn_trgm")
    op.drop_index('ix_search_document_tsv', table_name='search_document', postgresql_using='gin')
    op.drop_index(op.f('ix_search_document_base_part_number'), table_name='search_document')
    op.drop_table('search_document')
    op.drop_index(op.f('ix_actuator_rating_base_part_number'), table_name='actuator_rating')
    op.drop_table('actuator_rating')
    op.drop_index(op.f('ix_actuator_electrical_base_part_number'), table_name='actuator_electrical')
    op.drop_table('actuator_electrical')
    op.drop_index(op.f('ix_actuator_model_code'), table_name='actuator')
    op.drop_table('actuator')
    op.drop_table('source_table')
