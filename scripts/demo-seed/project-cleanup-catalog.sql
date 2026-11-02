DROP TABLE IF EXISTS pg_temp.demo_seed_project_cleanup_catalog;
CREATE TEMP TABLE demo_seed_project_cleanup_catalog (
    project_id UUID PRIMARY KEY,
    old_name TEXT NOT NULL,
    old_description TEXT NULL,
    old_description_mode TEXT NOT NULL CHECK (old_description_mode IN ('exact', 'blank')),
    new_name TEXT NOT NULL UNIQUE,
    new_description TEXT NOT NULL CHECK (new_description <> '')
) ON COMMIT DROP;

INSERT INTO demo_seed_project_cleanup_catalog (
    project_id,
    old_name,
    old_description,
    old_description_mode,
    new_name,
    new_description
)
VALUES
    (
        'b3c577d9-bede-458e-a523-ca094f79cf11',
        'testfggp',
        'opis2hgfhfghf',
        'exact',
        'Learning Platform Refresh',
        'Modernization of the learning portal, course catalog and participant onboarding experience.'
    ),
    (
        'c65a2c74-4884-48bb-acd8-9602badc55de',
        'fdsfasef',
        NULL,
        'blank',
        'Supplier Quality Dashboard',
        'Dashboard for supplier quality metrics, audits and corrective action tracking.'
    ),
    (
        '0c33f71d-d4da-4cfd-bbab-ec99285de371',
        'a',
        NULL,
        'blank',
        'Inventory Forecasting',
        'Demand forecasting and inventory planning for seasonal product lines.'
    ),
    (
        'e2a7ab54-1c05-4629-8d18-f0c26af7074a',
        's',
        NULL,
        'blank',
        'Order Fulfillment Portal',
        'Portal for order processing, fulfillment status and delivery exception handling.'
    ),
    (
        '51952efb-5ccb-48ec-bf91-92f3a921a24e',
        '22222',
        NULL,
        'blank',
        'Product Catalog Modernization',
        'Structured product catalog with improved search, attributes and publishing workflows.'
    ),
    (
        'b72b5bad-0a38-488c-b97e-4e1d429ae21a',
        'sssss',
        NULL,
        'blank',
        'Warehouse Operations',
        'Operational tools for inbound stock, picking, packing and warehouse performance.'
    ),
    (
        '98aadacb-4a32-4261-8c07-c739f26da987',
        'ssssss',
        NULL,
        'blank',
        'Sustainability Reporting',
        'Collection and reporting of sustainability indicators across suppliers and products.'
    ),
    (
        '4389f10e-59c4-4f8a-9cb2-d82613db3283',
        'ssss',
        'sqqs',
        'exact',
        'Retail Partner Hub',
        'Shared workspace for retail partners, product launches and commercial materials.'
    ),
    (
        '503df73c-5ed8-4849-be79-8bd202f947ea',
        'wwwws',
        'sswdsafasf',
        'exact',
        'Brand Campaign Planner',
        'Planning workspace for campaign calendars, creative approvals and channel delivery.'
    ),
    (
        '4a50b93c-6f7b-4cfd-b366-da071a3024b5',
        'sasadasf',
        'rhjh',
        'exact',
        'Portfolio Website Refresh',
        'Redesign and implementation of the public portfolio and case study experience.'
    ),
    (
        '51c0e908-ddb3-434d-ac7a-b992df853914',
        'asFAFSADGS',
        'DSAFAEf',
        'exact',
        'Beauty Commerce Analytics',
        'Analytics for product discovery, conversion and repeat purchase behavior.'
    ),
    (
        '8ba09b81-76c9-4b1c-be1d-cb16159b3b50',
        'Warszgtaty firmowe - omó÷wienie nowych ciekawych tematów do oceny nowych tematów',
        NULL,
        'blank',
        'Corporate Workshop Program',
        'Planning and delivery of internal workshops, including topics, facilitators and feedback.'
    );
