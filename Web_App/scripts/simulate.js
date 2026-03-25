import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { initializeApp } from 'firebase/app';
import { getFirestore, collection, addDoc, serverTimestamp } from 'firebase/firestore';

// Parse .env manually to avoid adding new dependencies
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.resolve(__dirname, '../.env');

let envContent = '';
try {
  envContent = fs.readFileSync(envPath, 'utf8');
} catch (e) {
  console.error("Failed to read .env file. Make sure you are running from the Web_App directory.");
  process.exit(1);
}

const env = {};
envContent.split('\n').forEach(line => {
  const match = line.match(/^\s*([\w.-]+)\s*=\s*(.*)?\s*$/);
  if (match) {
    env[match[1]] = match[2];
  }
});

const firebaseConfig = {
  apiKey: env.VITE_FIREBASE_API_KEY,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: env.VITE_FIREBASE_APP_ID
};

console.log("Connecting to Firebase...");
const app = initializeApp(firebaseConfig);
const db = getFirestore(app);

const CLASSES = [
  '100CanadianDollar', '10CanadianDollar', '20CanadianDollar', 
  '50CanadianDollar', '5CanadianDollar', 'Crosswalks', 
  'Important Text', 'car', 'green pedestrian light', 
  'pedestrian light', 'red pedestrian light', 'stop'
];

async function simulateDetection() {
  const randomClass = CLASSES[Math.floor(Math.random() * CLASSES.length)];
  const confidence = 0.5 + (Math.random() * 0.49); // between 50% and 99%
  const now = new Date();

  // The fields exactly match what the Android app pushes
  const docData = {
    className: randomClass,
    confidence: Number(confidence.toFixed(3)),
    timestamp: now.toLocaleTimeString(),
    createdAt: serverTimestamp() // To allow ordering in queries
  };

  try {
    const docRef = await addDoc(collection(db, 'detections'), docData);
    console.log(`[SIMULATION] Logged: ${randomClass} (${(confidence * 100).toFixed(1)}%) - ID: ${docRef.id}`);
  } catch (error) {
    console.error("Error adding document to Firestore: ", error);
  }
}

console.log("Starting Web App Simulation...");
console.log("Press Ctrl+C to stop.\n");

// Send one immediately, then loop every 3 seconds
simulateDetection();
setInterval(simulateDetection, 3000);
