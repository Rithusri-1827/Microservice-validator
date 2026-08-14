-- ACT-5 / CLR-2 defaults, canonical units (cpu millicores, memory bytes).
INSERT INTO environments(name, capacity, settle_delay_s, order_index) VALUES
  ('dev',        '{"cpu": 16000,  "memory": 68719476736}',  60,  0),
  ('staging',    '{"cpu": 32000,  "memory": 137438953472}', 120, 1),
  ('production', '{"cpu": 128000, "memory": 549755813888}', 120, 2);
