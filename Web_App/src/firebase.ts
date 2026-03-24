// Import the functions you need from the SDKs you need
import { initializeApp } from "firebase/app";
import { getFirestore } from "firebase/firestore";

// Your web app's Firebase configuration
const firebaseConfig = {
  apiKey: "AIzaSyCRVlbESWjDA5aX65EM86fWwVJpMOl1duo",
  authDomain: "myeyesanalytics.firebaseapp.com",
  projectId: "myeyesanalytics",
  storageBucket: "myeyesanalytics.firebasestorage.app",
  messagingSenderId: "740772671002",
  appId: "1:740772671002:web:7d8f901f7b87fb41966ba3"
};

// Initialize Firebase
const app = initializeApp(firebaseConfig);
export const db = getFirestore(app);
