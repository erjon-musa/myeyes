import { useState, useEffect } from 'react';
import './App.css';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, Title, Tooltip, Legend, ArcElement } from 'chart.js';
import { Bar } from 'react-chartjs-2';
import { Eye, Activity, Box, Clock } from 'lucide-react';
import { collection, onSnapshot, query, orderBy, limit } from 'firebase/firestore';
import { db } from './firebase';

ChartJS.register(CategoryScale, LinearScale, BarElement, Title, Tooltip, Legend, ArcElement);

// Mock Data Types
type DetectionEvent = {
  id: string;
  className: string;
  confidence: number;
  timestamp: string;
};

function App() {
  const [detections, setDetections] = useState<DetectionEvent[]>([]);
  const [isLive, setIsLive] = useState(true);

  // Listen to live data from Firebase
  useEffect(() => {
    if (!isLive) return;
    
    const q = query(
      collection(db, "detections"),
      orderBy("createdAt", "desc"),
      limit(50)
    );

    const unsubscribe = onSnapshot(q, (querySnapshot) => {
      const liveData: DetectionEvent[] = [];
      querySnapshot.forEach((doc) => {
        const data = doc.data();
        liveData.push({
          id: doc.id,
          className: data.className || 'Unknown',
          confidence: data.confidence || 0,
          timestamp: data.timestamp || new Date().toLocaleTimeString(),
        });
      });
      
      if (liveData.length > 0) {
        setDetections(liveData);
      }
    });

    return () => unsubscribe();
  }, [isLive]);

  // Aggregate data for charts
  const classCounts = detections.reduce((acc, curr) => {
    acc[curr.className] = (acc[curr.className] || 0) + 1;
    return acc;
  }, {} as Record<string, number>);

  const sortedClasses = Object.entries(classCounts).sort((a, b) => b[1] - a[1]).slice(0, 5);
  
  const barChartData = {
    labels: sortedClasses.map(c => c[0]),
    datasets: [
      {
        label: 'Detections',
        data: sortedClasses.map(c => c[1]),
        backgroundColor: 'rgba(59, 130, 246, 0.8)',
        borderRadius: 4,
      }
    ]
  };

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
    },
    scales: {
      y: {
        beginAtZero: true,
        grid: { color: 'rgba(255, 255, 255, 0.05)' },
        ticks: { color: '#a0aab2' }
      },
      x: {
        grid: { display: false },
        ticks: { color: '#a0aab2' }
      }
    }
  };

  const highConfidenceCount = detections.filter(d => d.confidence > 0.9).length;

  return (
    <div className="app-container">
      <header>
        <div className="brand">
          <Eye size={32} color="var(--accent)" />
          <h1>MyEyes Analytics</h1>
        </div>
        <div className="status-badge" onClick={() => setIsLive(!isLive)} style={{cursor: 'pointer', opacity: isLive ? 1 : 0.6}}>
          <div className="status-dot" style={{ backgroundColor: isLive ? 'var(--success)' : 'var(--danger)'}}></div>
          {isLive ? 'Live Stream Active' : 'Dashboard Paused'}
        </div>
      </header>

      <main className="dashboard-grid">
        {/* Top Stat Cards */}
        <div className="card stat-card">
          <div className="stat-icon"><Activity size={24} /></div>
          <div className="stat-title">Total Detections Logged</div>
          <div className="stat-value">{detections.length}</div>
        </div>

        <div className="card stat-card">
          <div className="stat-icon"><Box size={24} /></div>
          <div className="stat-title">Unique Classes</div>
          <div className="stat-value">{Object.keys(classCounts).length}</div>
        </div>

        <div className="card stat-card">
          <div className="stat-icon"><Clock size={24} /></div>
          <div className="stat-title">High Confidence ({">"}90%)</div>
          <div className="stat-value">{highConfidenceCount}</div>
        </div>

        {/* Main Chart */}
        <div className="card chart-card">
          <h2 className="card-title">Top Detected Objects</h2>
          <div style={{ height: '300px' }}>
            <Bar data={barChartData} options={chartOptions} />
          </div>
        </div>

        {/* Recent Detections List */}
        <div className="card log-card">
          <h2 className="card-title">Recent Detections</h2>
          <div className="detection-list">
            {detections.slice(0, 5).map(det => (
              <div key={det.id} className="detection-item">
                <div className="detection-info">
                  <span className="detection-class">{det.className}</span>
                  <span className="detection-time">{det.timestamp}</span>
                </div>
                <div className="detection-confidence" style={{ color: det.confidence > 0.9 ? 'var(--success)' : 'var(--accent)' }}>
                  {(det.confidence * 100).toFixed(1)}%
                </div>
              </div>
            ))}
            {detections.length === 0 && (
              <div style={{ color: 'var(--text-secondary)', textAlign: 'center', marginTop: '2rem' }}>
                Waiting for real-time detections from the Android App... 
                <br /><br />
                <span style={{fontSize: '0.85rem'}}>Make sure the Android App is running and camera is active!</span>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}

export default App;
