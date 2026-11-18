-- Approver of last resort when a stage resolves to nobody (no division head for the unit, or only the requester).
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description)
VALUES ('workflow.fallback-role', 'HR_ADMIN', 'WORKFLOW', 'STRING',
        'Role that receives a request when its stage has no eligible approver and no escalation role is set')
ON CONFLICT (setting_key) DO NOTHING;
