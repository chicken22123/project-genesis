import sqlite3
conn = sqlite3.connect(r'C:\Users\strai\AppData\Roaming\ModrinthApp\app.db')
cur = conn.cursor()
for row in cur.execute("SELECT id, name, path, install_stage, last_played FROM instances ORDER BY last_played DESC LIMIT 20"):
    print(row)
