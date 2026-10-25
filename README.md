# Spatial Restaurant Finder (DSA Case Study)

A high-performance spatial command-line application that fetches nearby restaurants from Google Places, indexes them in an in-memory 2D KD-Tree, and performs fast $k$-nearest-neighbour ($k$-NN) branch-and-bound queries with Haversine distance calculations, multi-criteria filtering, and continuous multi-city sessions in a single run.

---

## 🔑 Setting Up Your Google Maps API Key

The application requires a Google Maps API Key with access to the **Places API** and **Geocoding API**.

### 1. Obtain an API Key from Google Cloud
1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (or select an existing one) and ensure **Billing** is enabled.
3. In the search bar or library, enable:
   - **Places API** (or Places API New)
   - **Geocoding API**
4. Navigate to **APIs & Services > Credentials**.
5. Click **+ Create Credentials > API Key**.
6. Copy the generated key.

---

### 2. Configure the API Key

You can configure your key in either of two ways:

#### Option A: Edit the `.env` file (Recommended)
Open `.env` (in this directory or inside `restaurant-finder/`) and add your key:
```env
GOOGLE_MAPS_API_KEY=AIzaSy...your_actual_key_here...
```

#### Option B: Set as an Environment Variable
- **In PowerShell (current session)**:
  ```powershell
  $env:GOOGLE_MAPS_API_KEY="AIzaSy...your_actual_key_here..."
  ```
- **In PowerShell (persistent user environment variable)**:
  ```powershell
  [System.Environment]::SetEnvironmentVariable("GOOGLE_MAPS_API_KEY", "AIzaSy...your_actual_key_here...", "User")
  ```
- **In macOS / Linux**:
  ```bash
  export GOOGLE_MAPS_API_KEY="AIzaSy...your_actual_key_here..."
  ```

---

## 🚀 Running the Application

### Option 1: Quick Launcher (Easiest)
- **Double-click** `run.bat` in File Explorer, or
- Run in PowerShell:
  ```powershell
  .\run.ps1
  ```

### Option 2: Run with Java directly (Packaged Fat JAR)
```powershell
java -jar target\restaurant-finder-1.0.0-all.jar
```

### Option 3: Run with Maven
From inside the `restaurant-finder/` directory:
```powershell
mvn compile exec:java
```

---

## 🍽️ Features & Controls

### 1. Continuous Multi-City Session (Single Run)
- Query any city, landmark, address, or `lat,lng` coordinate.
- When done querying a city, you can seamlessly switch to another city without restarting the application!
- **Commands at query prompt**:
  - **`c`** or **`city`**: Instantly switch to a new city/location.
  - **`f`** or **`filter`**: Adjust filters (Diet, Rating, Cuisine) on the fly for the current indexed city.
  - **`q`** or **`quit`**: Finish current city queries and choose whether to search another city or exit.
  - Type **`q`** at the city prompt at any time to exit cleanly.

### 2. Intelligent Cuisine Detection & Filtering
- Automatically classifies places into authentic cuisine categories (e.g., South Indian, North Indian, Biryani & Mughlai, Italian & Pizza, Chinese & Asian, Fast Food, Cafe, Bakery & Desserts, Grill & BBQ, Seafood, Street Food, etc.) using Google Places data and intelligent keyword analysis.
- **Dynamic Cuisine Discovery**: When indexing a city, a frequency breakdown shows the exact cuisines available in that area.
- **Cuisine Filter**: Filter by choosing from the top cuisines in that city or type any custom cuisine/dish keyword (e.g. `Biryani`, `Pizza`, `South Indian`).
- **Results Table**: Features a dedicated `Cuisine` column alongside `Rank`, `Name`, `Distance`, `Rating`, `Diet`, and `Full Address`.

### 3. Dietary Filter
- `[1] All`: Shows all nearby dining spots.
- `[2] Pure Veg`: Filters specifically for verified vegetarian and vegan establishments.
- `[3] Non-Veg`: Filters for restaurants serving meat and non-veg specialties.

### 4. Minimum Rating Filter
- Filter by minimum star rating (e.g. `4.0`, `4.3`, `4.5` or press Enter for any rating).

---

## 🧠 Data Structures & Algorithms (DSA) Concepts

1. **2D KD-Tree (Spatial Index)**:
   - Alternating splitting axes: Latitude (depth % 2 == 0) and Longitude (depth % 2 == 1).
   - Balanced tree construction with median selection in $O(N \log^2 N)$ build time, yielding a balanced height of $\lceil \log_2(N + 1) \rceil$.
2. **Branch-and-Bound $k$-Nearest Neighbour ($k$-NN) Search**:
   - Uses a Max-Heap Priority Queue of capacity $k$ to track the closest candidates.
   - Spatial bounding-plane distance pruning ($d_{\text{plane}}^2 \ge \text{heap.peek()}$) reduces search from $O(N)$ linear scan to average $O(\log N)$.
3. **Multi-Criteria Predicate Filtering**:
   - Pruning and heap insertion seamlessly evaluate composable predicates (Diet $\land$ Rating $\land$ Cuisine).
4. **Hash Map Frequency Counting**:
   - $O(N)$ frequency analysis to discover and rank the cuisine distribution of any city.
5. **Geodesic Distance Metric**:
   - Equirectangular projection coordinate scaling with spherical Haversine formula calculation for exact real-world ground distances.
