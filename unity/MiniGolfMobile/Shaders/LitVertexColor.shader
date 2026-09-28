// Standard lit shader that can also multiply in the mesh's vertex colours, which many
// low-poly course models use instead of textures. Used by Mini Golf > Fix Materials.
Shader "MiniGolf/Lit Vertex Color"
{
    Properties
    {
        _Color ("Color", Color) = (1,1,1,1)
        _MainTex ("Albedo", 2D) = "white" {}
        _BumpMap ("Normal Map", 2D) = "bump" {}
        _EmissionMap ("Emission", 2D) = "white" {}
        [HDR] _EmissionColor ("Emission Color", Color) = (0,0,0,1)
        _UseVertexColor ("Use Vertex Color", Range(0,1)) = 0
        _Cutoff ("Alpha Cutoff", Range(0,1)) = 0
        _Glossiness ("Smoothness", Range(0,1)) = 0.2
    }
    SubShader
    {
        Tags { "RenderType"="Opaque" }
        LOD 200
        Cull Back

        CGPROGRAM
        #pragma surface surf Standard fullforwardshadows addshadow
        #pragma target 3.0

        sampler2D _MainTex;
        sampler2D _BumpMap;
        sampler2D _EmissionMap;
        fixed4 _Color;
        half4 _EmissionColor;
        half _UseVertexColor;
        half _Cutoff;
        half _Glossiness;

        struct Input
        {
            float2 uv_MainTex;
            float4 color : COLOR;
        };

        void surf (Input IN, inout SurfaceOutputStandard o)
        {
            fixed4 c = tex2D(_MainTex, IN.uv_MainTex) * _Color;
            c.rgb *= lerp(fixed3(1,1,1), IN.color.rgb, _UseVertexColor);
            clip(c.a - _Cutoff);
            o.Albedo = c.rgb;
            o.Normal = UnpackNormal(tex2D(_BumpMap, IN.uv_MainTex));
            o.Emission = tex2D(_EmissionMap, IN.uv_MainTex).rgb * _EmissionColor.rgb;
            o.Smoothness = _Glossiness;
            o.Alpha = c.a;
        }
        ENDCG
    }
    FallBack "Diffuse"
}
