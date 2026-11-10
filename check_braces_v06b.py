import glob, sys
files = [
 'app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt',
 'app/src/main/java/me/rerere/rikkahub/ui/theme/PresetTheme.kt',
 'app/src/main/java/me/rerere/rikkahub/ui/theme/presets/NazarickTheme.kt',
 'app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_39_40.kt',
 'app/src/main/java/me/rerere/rikkahub/data/db/AppDatabaseFactory.kt',
 'app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt',
 'app/src/test/java/me/rerere/rikkahub/data/datastore/AssistantRetirementTest.kt',
 'app/src/test/java/me/rerere/rikkahub/ui/theme/NazarickThemeTest.kt',
 'app/src/main/java/me/rerere/rikkahub/data/guardians/GuardianSeed.kt',
 'app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt',
]
bad = 0
for f in files:
    s = open(f, encoding='utf-8').read()
    b, p = s.count('{') - s.count('}'), s.count('(') - s.count(')')
    print(f.split('/')[-1], 'braces', b, 'parens', p)
    if b or p:
        bad += 1
sys.exit(1 if bad else 0)
