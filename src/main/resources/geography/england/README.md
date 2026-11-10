# England (GB-ENG) data

Synthea uses this folder when `generate.geography.country_code = GB-ENG`, which is the default in
`synthea.properties`. The codes are the UK government country codes (ISO 3166-2), listed at
https://www.gov.uk/government/publications/open-standards-for-government/country-codes.
`org.mitre.synthea.world.geography.Nation` selects the nation and finds its files.

Setting the nation to England also turns on:

- NHS numbers (Modulus 11) for each patient, used in FHIR identifiers and in output file names.
- NHS Data Dictionary ethnic categories (UK Core `EthnicCategoryEngland`), from `ethnicity.json`.
- UK addresses and birthplaces (country `GB`) and passports with the `passport-GBR` system.
- The NICE NG136 hypertension pathway. `hypertension.json` routes to it on the `nation` attribute.

| File | Purpose | Setting that overrides it |
|------|---------|---------------------------|
| `demographics.csv` | Towns in the South West region, with ONS census ethnic group columns | `generate.demographics.default_file` |
| `postcodes.csv` | Postcode districts with coordinates | `generate.geography.zipcodes.default_file` |
| `sdoh.csv` | Social determinants of health by county | `generate.geography.sdoh.default_file` |
| `timezones.csv` | Time zone for each region | `generate.geography.timezones.default_file` |
| `names.yml` | English names and UK street types | `generate.names.default_file` |
| `ethnicity.json` | Ethnic groups, NHS ethnic category weights and displays, and main language by group | `generate.demographics.ethnicity_file` |
| `immunization_schedule.json` | NHS England routine immunisation schedule, including historical changes | `generate.immunization_schedule` |
| `vital_sign_snomed.json` | SNOMED CT observable entity codes added next to LOINC vital sign codes in FHIR R4 | `exporter.fhir.vital_sign_snomed_file` |
| `providers/` | NHS hospitals, GP practices and urgent care sites in the South West (see `providers/README.md`) | `generate.providers.<type>.default_file` |
| `payers/` | NHS England as the single payer | `generate.payers.*` |

Synthea looks for each file in this order:

1. The setting, if present. An empty value means the resource is not used.
2. The file in this folder. If the file is missing, the resource is not used; for example there are
   no nursing, hospice or VA providers.

When the country code is not a UK nation (for example `US`), Synthea uses the upstream US files.

## Other UK nations

`GB-WLS` (or `GB-CYM`), `GB-SCT` and `GB-NIR` stop with an error until they are implemented.
Wales, Scotland and Northern Ireland each differ from England in some of these ways:

- Patient identifiers. Scotland uses CHI numbers and Northern Ireland uses Health and Care (H&C)
  numbers.
- Census demographics and ethnic categories.
- Providers.
- Clinical guidance.

To add a nation:

1. Create `geography/wales/` (or `scotland/`, `northern_ireland/`) with the same files.
2. Give the nation its folder and default region in `Nation`.
3. Add any identifier generator it needs.
