# England (South West) provider data

These are the providers for England (`generate.geography.country_code = GB-ENG`, the default
in `synthea.properties`); see `../README.md`. They are curated from the provider
files in the [NHSDigital/swpc_synthea-spr-poc](https://github.com/NHSDigital/swpc_synthea-spr-poc)
fork. Those files hold NHS sites in the South West region.

| File | Contents |
|------|----------|
| `hospitals.csv` | NHS trust sites whose name contains HOSPITAL or INFIRMARY (ambulance stations are excluded). `emergency=true` only for the ten major acute sites with a type 1 A&E: Derriford, Torbay, Royal Devon & Exeter (Wonford), North Devon District, Yeovil District, Dorset County, Poole (Emergency Dept), Royal Bournemouth, Gloucestershire Royal and Cheltenham General. |
| `urgent_care_facilities.csv` | Minor injury units, urgent treatment centres, urgent care centres and NHS walk-in centres. Prescriber, nurse-prescriber, team, service and out-of-hours entries are excluded. |
| `primary_care_facilities.csv` | GP surgeries, medical centres, health centres and practices. Screening, out-of-hours, specialist services, hospices, prisons and similar sites are excluded. |

## Curation steps

These steps were applied to the SWPC files:

- Removed rows without coordinates.
- Removed duplicate rows, matching on name and postcode.
- Normalised postcodes to `OUTWARD INWARD` form.
- Dropped the US CMS columns (`provider_num`, `npi`, `category`, `provider_type_code`, `fips_county`, and others). Some of these were corrupted by Excel, for example `01-Feb` and `Dec-70`. Synthea generates NPIs.
- Used the correct specialty column name `CLINICAL NURSE SPECIALIST` (SWPC had `CLINICAL_NURSE_SPECIALIST`, which did not match any specialty).

## Columns

The files use a `postcode` column. Synthea reads it as the provider's zip, and the column name
`zip` is also accepted.

The specialty columns set how many clinicians each site gets:

- GPs: 5 per practice, 1 per hospital or urgent care site.
- Midwives: 1 per hospital and per practice.
- Clinical nurse specialists: 1 per hospital.

## Known gaps

The SWPC hospital data only covers trusts in Devon, Dorset, Gloucestershire and Somerset. Patients in
Cornwall, Bristol and Wiltshire therefore travel to the nearest hospital in those counties. Primary
and urgent care cover the whole region.

US-only provider types (VA, IHS, nursing, hospice, home health) have no file in this folder, so they
are not used.
