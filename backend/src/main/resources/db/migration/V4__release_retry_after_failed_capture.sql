-- A release that failed at capture moved no money, so its milestone may get a new release.
-- Still at most one live (not CAPTURE_FAILED) release per milestone.
ALTER TABLE releases DROP CONSTRAINT releases_milestone_id_key;
CREATE UNIQUE INDEX uq_releases_live_milestone ON releases(milestone_id) WHERE status <> 'CAPTURE_FAILED';
